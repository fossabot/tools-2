[![logo](Burningwave-logo.jpg "Burningwave")](https://www.burningwave.org/)

**Burningwave Tools** is a set of components based on Burningwave Core library that have high-level functionality

# Dependencies shrinking
By this functionality only the classes and resources strictly used by an application will be extracted and stored in a specified path. At the end of the execution of the task, a script will be created in the destination path to run the application using the extracted classes. **The dependency shrinkers can also be used to adapt applications written with Java old versions to Java 9 or later**.

The classes that deal the dependencies extraction are:
* **org.burningwave.tools.dependencies.Capturer**
* **org.burningwave.tools.dependencies.TwoPassCapturer**

It can be used indiscriminately or one or the other class: the first performs a normal scan, the second a deep scan.

To use dependencies shrinkers in your project add this to your pom:
```xml
<dependency>
    <groupId>org.burningwave</groupId>
    <artifactId>tools</artifactId>
    <version>0.9.12</version>
</dependency>	
```
<br/>

## Extractor mode

```java
package org.burningwave.tools.examples.twopasscapturer;

import static
    org.burningwave.core.assembler.StaticComponentsContainer.ManagedLoggersRepository;

import java.util.Collection;

import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.io.PathHelper;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.burningwave.tools.dependencies.TwoPassCapturer;

public class DependenciesExtractor {    
    
    public static void main(String[] args) throws Exception {
        long initialTime = System.currentTimeMillis();
        ComponentSupplier componentSupplier = ComponentContainer.getInstance();
        PathHelper pathHelper = componentSupplier.getPathHelper();
        Collection<String> paths = pathHelper.getPaths(
            PathHelper.MAIN_CLASS_PATHS,
            PathHelper.MAIN_CLASS_PATHS_EXTENSION
        );
        Result result = TwoPassCapturer.getInstance().captureAndStore(
            //Here you indicate the main class of your application            
            "my.class.that.contains.a.MainMethod",
            paths,
            //Here you indicate the destination path where extracted
            //classes and resources will be stored    
            System.getProperty("user.home") + "/Desktop/dependencies",
            true,
            //Here you indicate the waiting time after the main of your
            //application has been executed. This is useful, for example, 
            //for spring boot applications to make it possible, once started,
            //to run rest methods to continue extracting the dependencies
            0L
        );
        result.waitForTaskEnding();
        ManagedLoggersRepository.logInfo(
            DependenciesExtractor.class, 
            "Elapsed time: " + getFormattedDifferenceOfMillis(
                System.currentTimeMillis(), initialTime
            )
        );
    }
    
    private static String getFormattedDifferenceOfMillis(long value1, long value2) {
        String valueFormatted = String.format("%04d", (value1 - value2));
        return valueFormatted.substring(0, valueFormatted.length() - 3) + "," +
        valueFormatted.substring(valueFormatted.length() -3);
    }

}
```
<br/>

## Adapter mode
In this mode you can adapt a Java old version application to Java 9 or later. To use this mode simply load, by using PathHelper, the jdk libraries by which the target application was developed and **run the main of the application adapter with a jdk 9 or later**. In the example below we adapt a Java 8 application to Java 9 or later.
```java
package org.burningwave.tools.examples.twopasscapturer;

import static
    org.burningwave.core.assembler.StaticComponentsContainer.ManagedLoggersRepository;

import java.util.Collection;

import org.burningwave.core.assembler.ComponentContainer;
import org.burningwave.core.assembler.ComponentSupplier;
import org.burningwave.core.io.PathHelper;
import org.burningwave.tools.dependencies.Capturer.Result;
import org.burningwave.tools.dependencies.TwoPassCapturer;

public class ApplicationAdapter {    
    
    public static void main(String[] args) throws Exception {
        long initialTime = System.currentTimeMillis();
        ComponentSupplier componentSupplier = ComponentContainer.getInstance();
        PathHelper pathHelper = componentSupplier.getPathHelper();
        Collection<String> paths = pathHelper.getPaths(
            PathHelper.MAIN_CLASS_PATHS,
            PathHelper.MAIN_CLASS_PATHS_EXTENSION
        );
        String jdk8Home = "C:/Program Files/Java/jdk1.8.0_172";
        //Add jdk 8 library
        paths.addAll(
            pathHelper.loadPaths(
                "dependencies-capturer.additional-resources-path", 
                "//" + jdk8Home + "/jre/lib//children:.*\\.jar;" +
                "//" + jdk8Home + "/jre/lib/ext//children:.*\\.jar;"
            )
        );
        Result result = TwoPassCapturer.getInstance().captureAndStore(
            //Here you indicate the main class of your application            
            "my.class.that.contains.a.MainMethod",
            paths,
            //Here you indicate the destination path where extracted
            //classes and resources will be stored    
            System.getProperty("user.home") + "/Desktop/dependencies",
            true,
            //Here you indicate the waiting time after the main of your
            //application has been executed. This is useful, for example, 
            //for spring boot applications to make it possible, once started,
            //to run rest methods to continue extracting the dependencies
            0L
        );
        result.waitForTaskEnding();
        ManagedLoggersRepository.logInfo(
            DependenciesExtractor.class, 
            "Elapsed time: " + getFormattedDifferenceOfMillis(
                System.currentTimeMillis(),
                initialTime
            )
        );
    }
    
    private static String getFormattedDifferenceOfMillis(long value1, long value2) {
        String valueFormatted = String.format("%04d", (value1 - value2));
        return valueFormatted.substring(0, valueFormatted.length() - 3) + "," +
        valueFormatted.substring(valueFormatted.length() -3);
    }

}
```
