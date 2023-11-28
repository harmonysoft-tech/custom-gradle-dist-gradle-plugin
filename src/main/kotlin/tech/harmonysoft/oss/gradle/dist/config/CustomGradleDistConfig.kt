package tech.harmonysoft.oss.gradle.dist.config

import org.gradle.api.provider.Property

interface CustomGradleDistConfig {

    val gradleVersion: Property<String>
    val customDistributionName: Property<String>
    val customDistributionFileNameMapper: Property<CustomDistributionNameMapper>
    val customDistributionVersion: Property<String>
    val gradleDistributionType: Property<String>
    val skipContentExpansionFor: Property<Collection<String>>
    val rootUrlMapper: Property<GradleUrlMapper>
}