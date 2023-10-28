plugins {
    kotlin("jvm") version "1.9.10"
    id("com.gradle.plugin-publish") version "1.2.1"
    id("tech.harmonysoft.oss.gradle.release.paperwork") version "1.7.0"
}

group = "tech.harmonysoft"
version = "1.9"

gradlePlugin {
    website = "https://gradle-dist.oss.harmonysoft.tech/"
    vcsUrl = "https://github.com/denis-zhdanov/custom-gradle-dist-gradle-plugin"
    plugins {
        create("gradleDist") {
            id = "tech.harmonysoft.oss.custom-gradle-dist-plugin"
            implementationClass = "tech.harmonysoft.oss.gradle.dist.CustomGradleDistributionPlugin"
            displayName = "Custom Gradle Wrapper construction plugin"
            description = "Helps setting up custom Gradle wrapper construction"
            tags = listOf("wrapper")
        }
    }
}

repositories {
    mavenCentral()
}

dependencies {
    api(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("org.assertj:assertj-core:3.24.2")
    testImplementation("tech.harmonysoft:harmonysoft-common-test:1.92.0")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}