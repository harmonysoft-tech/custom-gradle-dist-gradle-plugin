package tech.harmonysoft.oss.gradle.dist

import org.gradle.api.Plugin
import org.gradle.api.Project
import tech.harmonysoft.oss.gradle.dist.config.CustomGradleDistConfig
import tech.harmonysoft.oss.gradle.dist.config.GradleUrlMapper

@Suppress("unused")
class CustomGradleDistributionPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val config = project.extensions.create("gradleDist", CustomGradleDistConfig::class.java)
        project.afterEvaluate {
            validateAndEnrich(config, it)
            project.tasks.register("buildGradleDist", BuildCustomGradleDistributionTask::class.java) {
                it.config.set(config)
            }
        }
    }

    private fun validateAndEnrich(config: CustomGradleDistConfig, project: Project) {
        validateGradleVersion(config)
        configureCustomDistributionVersionIfNecessary(config, project)
        configureCustomDistributionName(config)
        configureDistributionTypeIfNecessary(config)
        configureGradleUrlMapperIfNecessary(config)
        configurePathToExcludeFromExpansionIfNecessary(config)
    }

    private fun validateGradleVersion(config: CustomGradleDistConfig) {
        if (!config.gradleVersion.isPresent || config.gradleVersion.get().isBlank()) {
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

    private fun configureCustomDistributionVersionIfNecessary(config: CustomGradleDistConfig, project: Project) {
        val notSpecified = !config.customDistributionVersion.isPresent || config.customDistributionVersion.get().isBlank()
        if (notSpecified && project.version == Project.DEFAULT_VERSION) {
            throw IllegalStateException(
                """
                    can not build - custom gradle distribution version is undefined. Please specify it like below or use project.version as default:
    
                    gradleDist {
                        ${CustomGradleDistConfig::customDistributionVersion.name} = "1.0"
                    }
                """.trimIndent()
            )
        }
        if (notSpecified) {
            config.customDistributionVersion.set(project.version.toString())
        }
    }

    private fun configureCustomDistributionName(config: CustomGradleDistConfig) {
        val hasCustomName = config.customDistributionName.isPresent
                            && config.customDistributionName.get().isNotBlank()
        if (hasCustomName && config.customDistributionFileNameMapper.isPresent) {
            throw IllegalStateException(
                "the both '${CustomGradleDistConfig::customDistributionName.name}' and "
                + "'${CustomGradleDistConfig::customDistributionFileNameMapper.name}' are defined, "
                + "but only one of them is required"
            )
        }
        if (!hasCustomName && !config.customDistributionFileNameMapper.isPresent) {
            throw IllegalStateException(
                "one of '${CustomGradleDistConfig::customDistributionName.name}' or "
                + "'${CustomGradleDistConfig::customDistributionFileNameMapper.name}' must be configured"
            )
        }
        if (hasCustomName) {
            config.customDistributionFileNameMapper.set { gradleVersion, customDistributionVersion, gradleDistributionType, distributionName ->
                val prefix = "gradle-$gradleVersion-${config.customDistributionName.get()}-$customDistributionVersion"
                val suffix = "$gradleDistributionType.zip"
                distributionName?.let {
                    "$prefix-$it-$suffix"
                } ?: "$prefix-$suffix"
            }
        }
    }

    private fun configureDistributionTypeIfNecessary(config: CustomGradleDistConfig) {
        if (!config.gradleDistributionType.isPresent) {
            config.gradleDistributionType.set("bin")
        }
    }

    private fun configureGradleUrlMapperIfNecessary(config: CustomGradleDistConfig) {
        if (!config.rootUrlMapper.isPresent) {
            config.rootUrlMapper.set(GradleUrlMapper { version, type ->
                "https://services.gradle.org/distributions/gradle-$version-${type}.zip"
            })
        }
    }

    private fun configurePathToExcludeFromExpansionIfNecessary(config: CustomGradleDistConfig) {
        if (!config.skipContentExpansionFor.isPresent) {
            config.skipContentExpansionFor.set(emptyList())
        }
    }
}