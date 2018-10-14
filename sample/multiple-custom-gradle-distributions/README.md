## Overview

This is an example of using custom Gradle distributions for projects with different setup - we create *'library'* and *'service'* distributions and a sample project which uses *'service'*.

## Custom Distributions

[custom distribution](custom-distribution) project configures two custom Gradle distribution - [library](custom-distribution/src/main/resources/init.d/library/library.gradle) and [service](custom-distribution/src/main/resources/init.d/service/service.gradle). Both of them use [shared setup](custom-distribution/src/main/resources/include).  

Note: a cool feature of init scripts is that we can apply Gradle plugins from them. Unfortunately, it's necessary to do that by specifying complete plugin class name - import by plugin id is not supported there (some old Gradle design bug). 

## Client Project

[client project](client-project) is [configured](client-project/gradle/wrapper/gradle-wrapper.properties#L3) to use the '*service*' distribution and defines only [bare minimum](client-project/build.gradle) of the configuration.

## In Action

1. Build custom distribution  
    `pushd custom-distribution; ./gradlew build; popd`
2. Run the client project  
    `pushd client-project; ./gradlew bootRun; popd`  
3. Call a web server server started by the client project and ensure that it works  
    ```
    curl 127.0.0.1:8080/ping
    Hi there!
    ```