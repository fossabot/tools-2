/*
 * This file is part of Burningwave Core.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/tools
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2020 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.tools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.burningwave.Throwables;
import org.burningwave.core.Component;
import org.burningwave.core.ManagedLogger;
import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.classes.ClassCriteria;
import org.burningwave.core.classes.ClassHelper;
import org.burningwave.core.classes.JavaClass;
import org.burningwave.core.classes.MemoryClassLoader;
import org.burningwave.core.classes.hunter.ByteCodeHunter;
import org.burningwave.core.classes.hunter.ByteCodeHunter.SearchResult;
import org.burningwave.core.classes.hunter.ClassPathHunter;
import org.burningwave.core.classes.hunter.SearchConfig;
import org.burningwave.core.common.Strings;
import org.burningwave.core.function.QuadConsumer;
import org.burningwave.core.io.FileInputStream;
import org.burningwave.core.io.FileOutputStream;
import org.burningwave.core.io.FileSystemHelper.Scan;
import org.burningwave.core.io.FileSystemItem;
import org.burningwave.core.io.PathHelper;
import org.burningwave.core.io.Streams;
import org.burningwave.core.io.ZipInputStream;


public class TwoPassDependenciesCapturer implements Component {
	private ByteCodeHunter byteCodeHunter;
	private PathHelper pathHelper;
	private ClassHelper classHelper;
	private ClassPathHunter classPathHunter;
	
	private TwoPassDependenciesCapturer(
		PathHelper pathHelper,
		ByteCodeHunter byteCodeHunter,
		ClassPathHunter classPathHunter,
		ClassHelper classHelper
	) {
		this.byteCodeHunter = byteCodeHunter;
		this.classPathHunter = classPathHunter;
		this.pathHelper = pathHelper;
		this.classHelper = classHelper;
	}
	
	public static TwoPassDependenciesCapturer create(ComponentSupplier componentSupplier) {
		return new TwoPassDependenciesCapturer(
			componentSupplier.getPathHelper(),
			componentSupplier.getByteCodeHunter(),
			componentSupplier.getClassPathHunter(),
			componentSupplier.getClassHelper()
		);
	}
	
	public static TwoPassDependenciesCapturer getInstance() {
		return LazyHolder.getDependeciesCapturerInstance();
	}
	
	public Result capture(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		QuadConsumer<String, String, String, ByteBuffer>  javaClassConsumer,
		QuadConsumer<String, String, String, ByteBuffer>  resourceConsumer,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		final Result result;
		try (SearchResult searchResult = byteCodeHunter.findBy(
			SearchConfig.forPaths(
				baseClassPaths
			)
		)) {
			result = new Result(
				searchResult.getClassesFlatMap(), 
				javaClassConsumer,
				resourceConsumer
			);
		}
		BiConsumer<Result, String> classNamePutter =
			includeMainClass ? 
				(res, className) -> 
					res.put(className) 
				:(res, className) -> {
					if (!className.equals(mainClass.getName())) {
						res.put(className);
					}
				};
		result.findingTask = CompletableFuture.runAsync(() -> {
			ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
			Class<?> cls;
			try (MemoryClassLoader memoryClassLoader = new MemoryClassLoader(null, classHelper) {
				@Override
				public void addLoadedCompiledClass(String name, ByteBuffer byteCode) {
					super.addLoadedCompiledClass(name, byteCode);
					classNamePutter.accept(result, name);
				};
				
				@Override
			    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			    	Class<?> cls = super.loadClass(name, resolve);
			    	classNamePutter.accept(result, name);
			    	return cls;
			    }
				 @Override
				public URL getResource(String name) {
					URL resourceURL = contextClassLoader.getResource(name);
					if (resourceURL != null) {
						result.putResource(FileSystemItem.ofPath(resourceURL), name);
					}
					return resourceURL;
				}
				
				@Override
				public Enumeration<URL> getResources(String name) throws IOException {
					Enumeration<URL> resourcesURL = contextClassLoader.getResources(name);
					while (resourcesURL.hasMoreElements()) {
						URL resourceURL = resourcesURL.nextElement();
						result.putResource(FileSystemItem.ofPath(resourceURL), name);
					}
					return contextClassLoader.getResources(name);
				}
			    
			    @Override
			    public InputStream getResourceAsStream(String name) {
			    	Function<String, InputStream> inputStreamRetriever =
			    			name.endsWith(".class") ? 
			    				super::getResourceAsStream :
			    				contextClassLoader::getResourceAsStream;
			    	
			    	InputStream inputStream = inputStreamRetriever.apply(name);
			    	if (inputStream != null) {
			    		getResource(name);
			    	}
			    	return inputStreamRetriever.apply(name);
			    }
		
			}) {
				Thread.currentThread().setContextClassLoader(memoryClassLoader);
				for (Entry<String, JavaClass> entry : result.classPathClasses.entrySet()) {
					JavaClass javaClass = entry.getValue();
					memoryClassLoader.addCompiledClass(javaClass.getName(), javaClass.getByteCode());
				}
				try {
					cls = classHelper.loadOrUploadClass(mainClass, memoryClassLoader);
					cls.getMethod("main", String[].class).invoke(null, (Object)new String[]{});
					if (continueToCaptureAfterSimulatorClassEndExecutionFor != null && continueToCaptureAfterSimulatorClassEndExecutionFor > 0) {
						Thread.sleep(continueToCaptureAfterSimulatorClassEndExecutionFor);
					}
					if (recursive) {
						launchExternalCapturer(
							mainClass, result.getStore().getAbsolutePath(), baseClassPaths, 
							resourceConsumer != null, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor
						);
					}
				} catch (Throwable exc) {
					throw Throwables.toRuntimeException(exc);				
				} finally {
					Thread.currentThread().setContextClassLoader(contextClassLoader);
				}
			}
		});
		return result;
	}
	
	private void launchExternalCapturer(
		Class<?> mainClass, String destinationPath, Collection<String> baseClassPaths,
		boolean storeAllResources, boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) throws IOException, InterruptedException {
		String javaExecutablePath = System.getProperty("java.home") + "/bin/java";
		List<String> command = new LinkedList<String>();
        command.add(Strings.Paths.clean(javaExecutablePath));
        command.add("-cp");
        StringBuffer generatedClassPath = new StringBuffer("\"");
        Collection<String> classPathsToBeScanned = new LinkedHashSet<>(baseClassPaths);
        classPathsToBeScanned.remove(destinationPath);
        List<String> classPaths = FileSystemItem.ofPath(destinationPath).getChildren().stream().map(
        	child -> child.getAbsolutePath()
        ).collect(Collectors.toList());
        generatedClassPath.append(String.join(System.getProperty("path.separator"), classPaths));
        ClassPathHunter.SearchResult searchResult = classPathHunter.findBy(
			SearchConfig.forPaths(
				baseClassPaths
			).by(
				ClassCriteria.create().className(clsName -> 
					clsName.equals(this.getClass().getName()) || clsName.equals(ComponentSupplier.class.getName())
				)
			)
    	);
        Iterator<FileSystemItem> classPathIterator = searchResult.getClassPaths().iterator();
        while (classPathIterator.hasNext()) {
        	FileSystemItem classPath = classPathIterator.next();
        	if (!generatedClassPath.toString().contains(classPath.getAbsolutePath())) {	
	        	generatedClassPath.append(
	        		System.getProperty("path.separator")
	            );
	        	generatedClassPath.append(
	        		classPath.getAbsolutePath()
	        	);
	        	classPathsToBeScanned.remove(classPath.getAbsolutePath());
        	}
        }
        generatedClassPath.append("\"");
        command.add(generatedClassPath.toString());
        command.add(this.getClass().getName());
        command.add(mainClass.getName());
        String classPathsToBeScannedParam = "\"" + String.join(System.getProperty("path.separator"), classPathsToBeScanned) + "\"";
        command.add(classPathsToBeScannedParam);        
        command.add("\"" + destinationPath + "\"");
        command.add(Boolean.valueOf(storeAllResources).toString());
        command.add(Boolean.valueOf(includeMainClass).toString());
        command.add(continueToCaptureAfterSimulatorClassEndExecutionFor.toString());
        ProcessBuilder builder = new ProcessBuilder(command);

        Process process = builder.inheritIO().start();
        
        process.waitFor();

	}
	
	public static void main(String[] args) throws ClassNotFoundException {
		Class.forName(ManagedLogger.class.getName());
		String mainClassName = args[0];
		Collection<String> paths = Arrays.asList(args[1].split(System.getProperty("path.separator")));
		String destinationPath = args[2];
		boolean storeAllResources = Boolean.valueOf(args[3]);
		boolean includeMainClass = Boolean.valueOf(args[4]);
		long continueToCaptureAfterSimulatorClassEndExecutionFor = Long.valueOf(args[5]);
		Class<?> mainClass = Class.forName(mainClassName);
		
		TwoPassDependenciesCapturer.getInstance().captureAndStore(
			mainClass, paths, destinationPath, storeAllResources, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, false
		).waitForTaskEnding();
	}
	
	public Result captureAndStore(
		Class<?> mainClass,
		String destinationPath,
		boolean storeAllResources,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		return captureAndStore(mainClass, pathHelper.getMainClassPaths(), destinationPath, storeAllResources, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor);
	}
	
	private Result captureAndStore(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		String destinationPath,
		boolean storeResources,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor,
		boolean recursive
	) {
		Result dependencies = capture(
			mainClass,
			baseClassPaths, getStoreFunction(),
			storeResources ?
				getStoreFunction()
				: null,
			includeMainClass,
			continueToCaptureAfterSimulatorClassEndExecutionFor,
			true
		);
		dependencies.store = FileSystemItem.ofPath(destinationPath);
		return dependencies;
	}
	
	public Result captureAndStore(
		Class<?> mainClass,
		Collection<String> baseClassPaths,
		String destinationPath,
		boolean storeResources,
		boolean includeMainClass,
		Long continueToCaptureAfterSimulatorClassEndExecutionFor
	) {
		return captureAndStore(mainClass, baseClassPaths, destinationPath, storeResources, includeMainClass, continueToCaptureAfterSimulatorClassEndExecutionFor, true);
	}
	
	private QuadConsumer<String, String, String, ByteBuffer> getStoreFunction() {
		return (storeBasePath, resourceAbsolutePath, resourceRelativePath, resourceContent) -> {
			String finalPath = getStoreEntryBasePath(storeBasePath, resourceAbsolutePath, resourceRelativePath);
			FileSystemItem fileSystemItem = FileSystemItem.ofPath(finalPath + "/" + resourceRelativePath);
			if (!fileSystemItem.exists()) {
				Streams.store(fileSystemItem.getAbsolutePath(), resourceContent);
				logDebug("Resource {} has been stored to CLASSPATH {}", resourceRelativePath, finalPath);
			}
		};
	}
	
	
	protected String getStoreEntryBasePath(String storeBasePath, String itemAbsolutePath, String ItemRelativePath) {
		String finalPath = itemAbsolutePath;
		if (finalPath.chars().filter(ch -> ch == '/').count() > 1) {
			finalPath = finalPath.substring(0, finalPath.lastIndexOf(ItemRelativePath) - 1).substring(finalPath.indexOf("/") + 1);
			finalPath = "[" + finalPath.replace("/", "][") + "]";
		} else {
			finalPath = finalPath.replace("/", "");
		}
		return storeBasePath + "/" + finalPath;
	}
	
	Consumer<Scan.ItemContext<FileInputStream>> getFileSystemEntryStorer(
		String destinationPath
	) {
		return (scannedItemContext) -> {
			String finalRelativePath = Strings.Paths.clean(scannedItemContext.getInput().getAbsolutePath()).replaceFirst(
				Strings.Paths.clean(scannedItemContext.getBasePath().getAbsolutePath()),
				""
			);
			File file = new File(destinationPath + finalRelativePath);
			file.mkdirs();
			file.delete();
			try(FileOutputStream fileOutputStream = FileOutputStream.create(file, true)) {
				Streams.copy(scannedItemContext.getInput(), fileOutputStream);
			}
		};
	}
	
	
	Consumer<Scan.ItemContext<ZipInputStream.Entry>> getZipEntryStorer(
		String destinationPath
	) {
		return (scannedItemContext) -> {
			String finalRelativePath = Strings.Paths.clean(scannedItemContext.getInput().getAbsolutePath()).replaceFirst(
				Strings.Paths.clean(scannedItemContext.getBasePath().getAbsolutePath()),
				""
			);
			File file = new File(destinationPath + finalRelativePath);
			file.mkdirs();
			file.delete();
			try(InputStream inputStream = scannedItemContext.getInput().toInputStream(); FileOutputStream fileOutputStream = FileOutputStream.create(file, true)) {
				Streams.copy(inputStream, fileOutputStream);
			} catch (IOException e) {
				logError("Excpetion occurred while trying to copy " + scannedItemContext.getInput().getAbsolutePath());
			}
		};
	}
		
	public static class Result implements Component {
		private CompletableFuture<Void> findingTask;
		private final Map<String, JavaClass> classPathClasses;
		private Map<String, ByteBuffer> resources;
		private Map<String, JavaClass> result;
		private FileSystemItem store;
		private QuadConsumer<String, String, String, ByteBuffer> javaClassConsumer;
		private QuadConsumer<String, String, String, ByteBuffer> resourceConsumer;
		
		private Result(
			Map<String, JavaClass> classPathClasses,
			QuadConsumer<String, String, String, ByteBuffer> javaClassConsumer,
			QuadConsumer<String, String, String, ByteBuffer> resourceConsumer
		) {
			this.result = new ConcurrentHashMap<>();
			this.classPathClasses = new ConcurrentHashMap<>();
			this.resources = new ConcurrentHashMap<>();
			this.classPathClasses.putAll(classPathClasses);
			this.javaClassConsumer = javaClassConsumer;
			this.resourceConsumer = resourceConsumer;
		}
		
		public JavaClass load(String className) {
			for (Map.Entry<String, JavaClass> entry : classPathClasses.entrySet()) {
				if (entry.getValue().getName().equals(className)) {
					JavaClass javaClass = entry.getValue();
					result.put(entry.getKey(), javaClass);
					if (javaClassConsumer != null) {
						javaClassConsumer.accept(store.getAbsolutePath(), entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
					}
					return entry.getValue();
				}
			}
			return null;
		}
		
		public Collection<JavaClass> loadAll(Collection<String> classesName) {
			Collection<JavaClass> javaClassAdded = new LinkedHashSet<>();
			for (Map.Entry<String, JavaClass> entry : classPathClasses.entrySet()) {
				if (classesName.contains(entry.getValue().getName())) {
					JavaClass javaClass = entry.getValue();
					result.put(entry.getKey(), javaClass);
					javaClassAdded.add(javaClass);
					classesName.remove(javaClass.getName());
					if (javaClassConsumer != null) {
						javaClassConsumer.accept(store.getAbsolutePath(), entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
					}
				}
			}
			return javaClassAdded;
		}
		
		public void putResource(FileSystemItem fileSystemItem, String resourceName) {
			if (fileSystemItem.isFile() && fileSystemItem.exists()) {
				if (resourceConsumer != null) {
		    		resourceConsumer.accept(store.getAbsolutePath(), fileSystemItem.getAbsolutePath(), resourceName, fileSystemItem.toByteBuffer());
		    	}
			}
		}
		
		private JavaClass put(String className) {
			for (Map.Entry<String, JavaClass> entry : classPathClasses.entrySet()) {
				if (entry.getValue().getName().equals(className)) {
					result.put(entry.getKey(), entry.getValue());
					if (javaClassConsumer != null) {
						JavaClass javaClass = entry.getValue();
						javaClassConsumer.accept(store.getAbsolutePath(), entry.getKey(), javaClass.getPath(), javaClass.getByteCode());
					}
					return entry.getValue();
				}
			}
			return null;
		}
		
		public Map<String, JavaClass> get() {
			return result;
		}
		
		public CompletableFuture<Void> getFindingTask() {
			return this.findingTask;
		}
		
		public void waitForTaskEnding() {
			findingTask.join();
		}
		
		public FileSystemItem getStore() {
			return store;
		}
		
		@Override
		public void close() {
			findingTask.cancel(true);
			findingTask = null;
			classPathClasses.clear();
			resources.clear();
			resources = null;
			result.clear();
			result = null;
			store = null;
		}
	}
	
	private static class LazyHolder {
		private static final TwoPassDependenciesCapturer DEPENDECIES_CAPTURER_INSTANCE = TwoPassDependenciesCapturer.create(ComponentContainer.getInstance());
		
		private static TwoPassDependenciesCapturer getDependeciesCapturerInstance() {
			return DEPENDECIES_CAPTURER_INSTANCE;
		}
	}
}