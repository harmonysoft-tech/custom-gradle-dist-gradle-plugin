package tech.harmonysoft.oss.gradle.dist.config

fun interface CustomDistributionNameMapper {

    fun map(
        gradleVersion: String,
        customDistributionVersion: String,
        gradleDistributionType: String,
        distributionName: String?
    ): CharSequence
}