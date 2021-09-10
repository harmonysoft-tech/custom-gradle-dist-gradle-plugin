## License

See the [LICENSE](LICENSE.md) file for license rights and limitations (MIT).

## Overview
 
 This plugin facilitates custom Gradle wrappers construction. For example, we can define [common part](sample/multiple-custom-gradle-distributions/custom-distribution/src/main/resources/init.d/service/service.gradle) which is [shared](sample/multiple-custom-gradle-distributions/client-project/gradle/wrapper/gradle-wrapper.properties#L3) through a custom Gradle distribution and have a terse end-project Gradle setup like [this](sample/multiple-custom-gradle-distributions/client-project/build.gradle) (this is a complete *build.gradle* file):  
 
 ```groovy
bootRun {
    main = 'com.mycompany.MyApplication'
}
```
 
## Problem
 
 Gradle scripts quite often contain duplicate parts, that's especially true for micro-service architecture where there are many small servers and each of them has its own configuration.
   
 One solution to that is putting common parts to a Gradle plugin. However, such extension would be specific to particular company and is unlikely going to the Gradle plugin repository (to allow [shorthand access](https://docs.gradle.org/current/userguide/plugins.html#sec:plugins_block)).  
 That means that it still would be necessary to have a setup like below in every project:  
 ```groovy
 buildscript {
     repositorirs {
         maven {
             url 'http://artifactory.mycompany.com/external-dependencies-repo'
         }
         classpath 'com.mycompany:gradle-plugin:1.0.0'
     }
 }
 
 apply plugin: 'com.mycompany.gradle-plugin'

```

## Solution

Gradle automatically applies [init scripts](https://docs.gradle.org/current/userguide/init_scripts.html) from [_Gradle_ wrapper]((https://docs.gradle.org/current/userguide/gradle_wrapper.html))'s _init.d_ directory. That's why we can do the following:
1. Fetch a 'pure' _Gradle_ distribution of particular version
2. Put our scripts with common logic into it's _init.d_ directory
3. Store that modified _Gradle_ distribution in our repository
4. Reference that distribution from the _Gradle Wrapper_ config in our projects    
 
## Usage

### Configure Custom Distribution

1. Create new Gradle project (an empty *build.gradle*) 
2. Register the plugin there:
    ```groovy
    plugins {
        id 'tech.harmonysoft.oss.custom-gradle-dist-plugin' version '1.6'
    }
    ```
 3. Specify target settings in the `gradleDist {}` block.  
     *mandatory settings:*
     * *gradleVersion* - base Gradle wrapper version
     * *customDistributionName* - a unique identifier for the custom Gradle distribution
     * *customDistributionVersion* - custom distribution version
     
     *optional settings:*
     * *gradleDistributionType* - allows to specify base Gradle distribution type. *'bin'* and *'all'* [are available](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:adding_wrapper), *'bin'* is used by default  
     * *skipContentExpansionFor* - the plugin by default expands content of the files included into custom Gradle distribution by default (see below). That might cause a problem if we want to add some binary file like `*.jar` or `*.so`. This property holds an array of root paths relative to `init.d` which content shouldn't be expanded.  
       Example: consider the following project structure:
       ```
       init.d
         |__my.gradle
         |
         |__bin
             |
             |__profiler
                   |
                   |__agent.jar
                         |
                         |__linux-x64
                               |
                               |__agentti.so
       ```
       Here we want to expand content for `my.gradle`, but don't touch `bin/profiler/agent.jar` and `bin/profiler/linux-x64/agentti.so`. We can configure it as below:
       ```
       gradleDist {
         ...
         skipContentExpansionFor: [
           'bin/profiler'
         ]
       }
       ```
     * *rootUrlMapper* - a function which allows to build an url to the root base Gradle distribution path. This property is convenient in restricted environments where *https://service.gradle.org* is unavailable. We can deploy target Gradle distribution to a server inside the private network and use it as a base for our custom Gradle distributions. The function receives the following arguments:  
       * *version* - target base Gradle distribution version, e.g. *5.1*
       * *type* - target base Gradle distribution type, e.g. *bin*  
       
       Following implementation us used by default:  
       `return "https://services.gradle.org/distributions/gradle-$version-${type}.zip"` 
     
    Resulting *build.gradle* might look like below:  
    ```groovy
    plugins {
        id 'tech.harmonysoft.oss.custom-gradle-dist-plugin' version '1.6'
    }
    
    gradleDist {
        gradleVersion = '4.10'
        customDistributionVersion = '1.0'
        customDistributionName = 'my-project'
    }
    ```
4. Define common setup to be included to the custom Gradle distribution in the project's *src/main/resources/init.d* directory  
    
    Note that the plugin supports simple text processing engine - it's possible to put utility scripts to the *src/main/resources/include*. Their content is applied to files from *src/main/resources/init.d* using `$utility-script-name$` syntax.  
    
    For example we can have a file *src/main/resources/init.d/setup.gradle*:  
    ```groovy
    allprojects {
        $dependencies$
    }
    ```
    
    and the following files in the *src/main/resources/include* directory:  
    
    *src/main/resources/include/dependencies.gradle*:  
    
    ```groovy
    dependencies {
        compile 'com.fasterxml.jackson.core:jackson-core:$jackson-version$'
        compile 'com.fasterxml.jackson.module:jackson-module-kotlin:$jackson-version$'
    }
    ```  
    
    *src/main/resources/include/jackson-version.gradle*:  
    
    ```groovy
    2.9.6
    ```  
    
    When custom Gradle distribution is created, its *init.d* directory has *setup.gradle* file with the following content then:  
    
    ```groovy
    allprojects {
        compile 'com.fasterxml.jackson.core:jackson-core:2.9.6'
        compile 'com.fasterxml.jackson.module:jackson-module-kotlin:2.9.6'  
    }
    ```  
    
    Note that text processing might be nested, i.e. files from *src/main/resources/include* might refer to another files from the same directory through the `$file-name$` syntax.
    
    There is an alternative setup where we want to produce more than one Gradle wrapper distribution (e.g. '*android*' and '*server*'). In this situation corresponding directories should be done in the *src/main/resources/init.d*:  
    ```
    src
     |__ main
         |__ resources
                 |__ init.d
                       |__ library
                       |      |__ android-setup.gradle
                       |
                       |__ server
                              |__ server-setup.gradle   
    ```  
    
    Here is what we have after the build:  
    ```
    build
      |__ gradle-dist
               |__ gradle-4.10-my-project-1.0-android.zip
               |
               |__ gradle-4.10-my-project-1.0-server.zip
    ```
    
5. Build Gradle distribution(s)

    ```
    ./gradlew build
    ```
    
    The distribution(s) are located in the *build/gradle-dist*

### Configure Client Project

Just define your custom Gradle wrapper's location in the *gradle/wrapper/gradle-wrapper.properties* file:  
```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
distributionUrl=http\://mycompany.com/repository/chillout-release/com/mycompany/gradle-dist/gradle-4.10-my-project-1.0.zip
```

### Note About Applying Plugins

It's quite possible that we would like to apply common plugins in init scripts, e.g. our projects are all java and we want to specify `apply plugin: 'java'` in the init script.
  
Unfortunately, there is a known [old bug](https://github.com/gradle/gradle/issues/1322) in Gradle that non-bundled plugins can't be applied by id in init script.  

A solution is to apply them by fully qualified class name.  

E.g. given 'normal' plugins configuration:

```groovy
apply plugin: 'org.jetbrains.kotlin.jvm'
apply plugin: 'org.springframework.boot'
apply plugin: 'io.spring.dependency-management'
apply plugin: "com.jfrog.artifactory"
```

We should configure them like below in init script:  

```groovy
allprojects {
    apply plugin: org.jetbrains.kotlin.gradle.plugin.KotlinPlatformJvmPlugin
    apply plugin: org.springframework.boot.gradle.plugin.SpringBootPlugin
    apply plugin: io.spring.gradle.dependencymanagement.DependencyManagementPlugin
    apply plugin: org.jfrog.gradle.plugin.artifactory.ArtifactoryPlugin
}
```

## Examples

Complete working examples can be found [here](sample/README.md).

## Releases

[Release Notes](RELEASE.md).  

The latest plugin version can be found [here](https://plugins.gradle.org/plugin/tech.harmonysoft.oss.custom-gradle-dist-plugin).

## How to Contribute

* [report a problem/ask for enhancement](https://github.com/denis-zhdanov/custom-gradle-dist-gradle-plugin/issues)
* [submit a pull request](https://github.com/denis-zhdanov/custom-gradle-dist-gradle-plugin/pulls)
* [![paypal](https://www.paypalobjects.com/en_US/i/btn/btn_donateCC_LG.gif)](https://www.paypal.com/cgi-bin/webscr?cmd=_donations&business=3GJDPN3TH8T48&lc=EN&item_name=GradleDist&currency_code=USD&bn=PP%2dDonationsBF%3abtn_donateCC_LG%2egif%3aNonHosted)

## Contributors

* [Denis Zhdanov](https://github.com/denis-zhdanov)

## Feedback

Please use any of the channels below to provide your feedback, it's really valuable for me:
* [email](mailto:denzhdanov@gmail.com)