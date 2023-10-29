package tech.harmonysoft.oss.gradle.dist

import org.gradle.api.Plugin
import org.gradle.api.Project

@Suppress("unused")
class CustomGradleDistributionPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val config = project.extensions.create("gradleDist", CustomGradleDistConfig::class.java)
        project.afterEvaluate {
            validateAndEnrich(config)
            project.tasks.register("buildGradleDist", BuildCustomGradleDistributionTask::class.java) {
                it.config.set(config)
            }
        }
    }

    private fun validateAndEnrich(extension: CustomGradleDistConfig) {
        validateGradleVersion(extension)
        validateCustomDistributionVersion(extension)
        validateCustomDistributionName(extension)
        configureDistributionTypeIfNecessary(extension)
        configureGradleUrlMapperIfNecessary(extension)
        configurePathToExcludeFromExpansionIfNecessary(extension)
    }

    private fun validateGradleVersion(extension: CustomGradleDistConfig) {
        if (!extension.gradleVersion.isPresent || extension.gradleVersion.get().isBlank()) {
            throw IllegalStateException(
                """
                    can not build custom gradle distribution - base gradle version is undefined. Please specify it like below:
    
                    gradleDist {
                      ${CustomGradleDistConfig::gradleVersion.name} = "8.3"
                    }
                """.trimIndent()
            )
        }
    }

    private fun validateCustomDistributionVersion(extension: CustomGradleDistConfig) {
        if (!extension.customDistributionVersion.isPresent || extension.customDistributionVersion.get().isBlank()) {
            throw IllegalStateException(
                """
                    can not build - custom gradle distribution version is undefined. Please specify it like below:
    
                    gradleDist {
                        ${CustomGradleDistConfig::customDistributionVersion.name} = "1.0"
                    }
                """.trimIndent()
            )
        }
    }

    private fun validateCustomDistributionName(extension: CustomGradleDistConfig) {
        if (!extension.customDistributionName.isPresent || extension.customDistributionName.get().isBlank()) {
            throw IllegalStateException(
                """
                   can not build - custom gradle distribution project name is undefined. Please specify it like below:
    
                   gradleDist {
                       ${CustomGradleDistConfig::customDistributionName.name} = "my-project"
                   }
                """.trimIndent()
            )
        }
    }

    private fun configureDistributionTypeIfNecessary(extension: CustomGradleDistConfig) {
        if (!extension.gradleDistributionType.isPresent) {
            extension.gradleDistributionType.set("bin")
        }
    }

    private fun configureGradleUrlMapperIfNecessary(extension: CustomGradleDistConfig) {
        if (!extension.rootUrlMapper.isPresent) {
            extension.rootUrlMapper.set { version, type ->
                "https://services.gradle.org/distributions/gradle-$version-${type}.zip"
            }
        }
    }

    private fun configurePathToExcludeFromExpansionIfNecessary(extension: CustomGradleDistConfig) {
        if (!extension.skipContentExpansionFor.isPresent) {
            extension.skipContentExpansionFor.set(emptyList())
        }
    }
}