package tech.harmonysoft.oss.gradle.dist

import org.gradle.api.provider.Property

interface CustomGradleDistConfig {

    val gradleVersion: Property<String>
    val customDistributionName: Property<String>
    val customDistributionFileNameMapper: Property<(String, String, String, String?) -> String>
    val customDistributionVersion: Property<String>
    val gradleDistributionType: Property<String>
    val skipContentExpansionFor: Property<Collection<String>>
    val rootUrlMapper: Property<(String, String) -> String>
}