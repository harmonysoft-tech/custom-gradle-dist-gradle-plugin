package tech.harmonysoft.oss.gradle.dist.config

fun interface GradleUrlMapper {

    fun map(version: String, type: String): CharSequence
}