package tech.harmonysoft.oss.gradle.dist

import org.gradle.api.Plugin
import org.gradle.api.Project
import tech.harmonysoft.oss.gradle.dist.config.CustomGradleDistConfig
import tech.harmonysoft.oss.gradle.dist.config.GradleUrlMapper

@Suppress("unused")
class CustomGradleDistributionPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val config = createAndConfigureExtension(project)

        project.tasks.register(
            "buildGradleDist",
            BuildCustomGradleDistributionTask::class.java,
            config
        )
        project.afterEvaluate {
            validateAndEnrich(config)
        }
    }

    private fun createAndConfigureExtension(project: Project): CustomGradleDistConfig {
        val config = project.extensions.create(
            "gradleDist", CustomGradleDistConfig::class.java
        )

        config.customDistributionVersion.convention(
            project.provider { project.version.toString() }
        )
        config.gradleDistributionType.convention("bin")
        project.layout.projectDirectory.dir("src/main/resources/include").let {
            if (it.asFile.exists()) config.utilityScriptsSourceDir.convention(it)
        }
        config.initScriptsSourceDir.convention(
            project.layout.projectDirectory.dir("src/main/resources/init.d")
        )
        config.skipContentExpansionFor.convention(emptyList())
        config.rootUrlMapper.convention(GradleUrlMapper { version, type ->
            "https://services.gradle.org/distributions/gradle-$version-${type}.zip"
        })

        return config
    }

    private fun validateAndEnrich(config: CustomGradleDistConfig) {
        validateGradleVersion(config)
        validateCustomDistributionVersion(config)
        configureCustomDistributionName(config)
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

    private fun validateCustomDistributionVersion(config: CustomGradleDistConfig) {
        if (!config.customDistributionVersion.isPresent || config.customDistributionVersion.get().isBlank()) {
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
}