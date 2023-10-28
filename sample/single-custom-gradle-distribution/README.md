## Overview

This is an example of using custom Gradle distribution for projects with the same setup - we create a single custom distribution and use it everywhere.

**Note:** java 21 is required

## Custom Distribution

[custom-distribution](custom-distribution) project defines common distribution setup in the [setup.gradle](custom-distribution/src/main/resources/init.d).

## Client Project

[client-project](client-project) is [configured](client-project/gradle/wrapper/gradle-wrapper.properties#L3) to use that custom distribution and defines only [bare minimum](client-project/build.gradle) of the configuration.

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