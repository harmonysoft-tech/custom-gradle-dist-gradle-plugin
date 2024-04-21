package tech.harmonysoft.oss.gradle.dist.config

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.Optional

interface CustomGradleDistConfig {

    @get:Input
    val gradleVersion: Property<String>

    @get:Optional
    @get:Input
    val customDistributionName: Property<String>

    @get:Optional
    @get:Nested
    val customDistributionFileNameMapper: Property<CustomDistributionNameMapper>

    @get:Input
    val customDistributionVersion: Property<String>

    @get:Input
    val gradleDistributionType: Property<String>

    @get:Optional
    @get:InputDirectory
    val utilityScriptsSourceDir: DirectoryProperty

    @get:InputDirectory
    val initScriptsSourceDir: DirectoryProperty

    @get:Input
    val skipContentExpansionFor: Property<Collection<String>>

    @get:Internal
    val rootUrlMapper: Property<GradleUrlMapper>
}
