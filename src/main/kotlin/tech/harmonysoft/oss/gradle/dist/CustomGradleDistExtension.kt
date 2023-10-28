package tech.harmonysoft.oss.gradle.dist

import org.gradle.api.provider.Property

interface CustomGradleDistExtension {

    val gradleVersion: Property<String>
    val customDistributionName: Property<String>
    val customDistributionVersion: Property<String>
    val gradleDistributionType: Property<String>
    val skipContentExpansionFor: Property<Collection<String>>
    val rootUrlMapper: Property<(String, String) -> String>
}