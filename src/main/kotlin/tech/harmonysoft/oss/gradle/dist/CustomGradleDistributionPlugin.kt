package tech.harmonysoft.oss.gradle.dist

import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import java.nio.channels.Channels
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Stack
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.tooling.BuildException

@Suppress("unused")
class CustomGradleDistributionPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("gradleDist", CustomGradleDistExtension::class.java)
        project.afterEvaluate {
            validateAndEnrich(extension)
            val task = project.tasks.findByName("build") ?: project.task("build")
            task.doLast {
                val baseDistribution = getBaseGradleDistribution(project, extension)
                val customDistributionsDir = getCustomDistributionsRootDir(project)
                remove(customDistributionsDir)
                Files.createDirectories(customDistributionsDir.toPath())

                val distributions = getDistributions(project)
                if (distributions.isEmpty()) {
                    prepareCustomDistribution(
                        distribution = null,
                        baseDistribution = baseDistribution,
                        project = project,
                        extension = extension
                    )
                } else {
                    for (distribution in distributions) {
                        prepareCustomDistribution(
                            distribution = distribution,
                            baseDistribution = baseDistribution,
                            project = project,
                            extension = extension
                        )
                    }
                }
            }
        }
    }

    private fun validateAndEnrich(extension: CustomGradleDistExtension) {
        validateGradleVersion(extension)
        validateCustomDistributionVersion(extension)
        validateCustomDistributionName(extension)
        configureDistributionTypeIfNecessary(extension)
        configureGradleUrlMapperIfNecessary(extension)
        configurePathToExcludeFromExpansionIfNecessary(extension)
    }

    private fun validateGradleVersion(extension: CustomGradleDistExtension) {
        if (!extension.gradleVersion.isPresent && extension.gradleVersion.get().isBlank()) {
            throw IllegalStateException(
                """
                    can not build custom gradle distribution - base gradle version is undefined. Please specify it like below:
    
                    gradleDist {
                      ${CustomGradleDistExtension::gradleVersion.name} = "8.3"
                    }
                """.trimIndent()
            )
        }
    }

    private fun validateCustomDistributionVersion(extension: CustomGradleDistExtension) {
        if (!extension.customDistributionVersion.isPresent || extension.customDistributionVersion.get().isBlank()) {
            throw IllegalStateException(
                """
                    can not build - custom gradle distribution version is undefined. Please specify it like below:
    
                    gradleDist {
                        ${CustomGradleDistExtension::customDistributionVersion.name} = "1.0"
                    }
                """.trimIndent()
            )
        }
    }

    private fun validateCustomDistributionName(extension: CustomGradleDistExtension) {
        if (!extension.customDistributionName.isPresent || extension.customDistributionName.get().isBlank()) {
            throw IllegalStateException(
                """
                   can not build - custom gradle distribution project name is undefined. Please specify it like below:
    
                   gradleDist {
                       ${CustomGradleDistExtension::customDistributionName.name} = "my-project"
                   }
                """.trimIndent()
            )
        }
    }

    private fun configureDistributionTypeIfNecessary(extension: CustomGradleDistExtension) {
        if (!extension.gradleDistributionType.isPresent) {
            extension.gradleDistributionType.set("bin")
        }
    }

    private fun configureGradleUrlMapperIfNecessary(extension: CustomGradleDistExtension) {
        if (!extension.rootUrlMapper.isPresent) {
            extension.rootUrlMapper.set { version, type ->
                "https://services.gradle.org/distributions/gradle-$version-${type}.zip"
            }
        }
    }

    private fun configurePathToExcludeFromExpansionIfNecessary(extension: CustomGradleDistExtension) {
        if (!extension.skipContentExpansionFor.isPresent) {
            extension.skipContentExpansionFor.set(emptyList())
        }
    }

    private fun getBaseGradleDistribution(project: Project, extension: CustomGradleDistExtension): File {
        return try {
            doGetBaseGradleDistribution(project, extension)
        } catch (e: Exception) {
            throw BuildException(
                "failed to prepare base gradle distribution of version ${extension.gradleVersion.get()}",
                e
            )
        }
    }

    private fun doGetBaseGradleDistribution(project: Project, extension: CustomGradleDistExtension): File {
        val gradleBaseName = "gradle-${extension.gradleVersion.get()}"
        val gradleZip = "$gradleBaseName-${extension.gradleDistributionType.get()}.zip"
        val baseGradleArchive = project.layout.buildDirectory.file("download/$gradleZip").get().asFile
        if (!baseGradleArchive.isFile) {
            val archiveDir = baseGradleArchive.parentFile
                    if (!archiveDir.isDirectory) {
                        val ok = archiveDir.mkdirs()
                        if (!ok) {
                            throw IllegalStateException(
                                "can't create directory ${archiveDir.canonicalPath} to store gradle distribution"
                            )
                        }
                    }
            download(
                project = project,
                fromUrl = extension.rootUrlMapper.get().invoke(
                    extension.gradleVersion.get(),
                    extension.gradleDistributionType.get()
                ),
                toFile = File(archiveDir, gradleZip)
            )
        }
        return baseGradleArchive
    }

    private fun download(project: Project, fromUrl: String, toFile: File) {
        val from = Channels.newChannel(URL(fromUrl).openStream())
        project.logger.lifecycle("about to download a gradle distribution from $fromUrl to ${toFile.canonicalPath}")
        FileOutputStream(toFile).channel.use {
            it.transferFrom(from, 0, Long.MAX_VALUE)
        }
        project.logger.lifecycle("downloaded a gradle distribution from $fromUrl to ${toFile.canonicalPath}")
    }

    private fun getCustomDistributionsRootDir(project: Project): File {
        return project.layout.buildDirectory.file("gradle-dist").get().asFile
    }

    private fun remove(toRemove: File) {
        if (toRemove.isDirectory) {
            toRemove.listFiles()?.forEach {
                remove(it)
            }
            val ok = toRemove.delete()
            if (!ok) {
                throw IllegalStateException("failed to remove directory ${toRemove.canonicalPath}")
            }
        } else if (toRemove.exists()) {
            val ok = toRemove.delete()
            if (!ok) {
                throw IllegalStateException("failed to remove file ${toRemove.canonicalPath}")
            }
        }
    }

    private fun getDistributions(project: Project): Collection<String> {
        val extensionRootDir = getExtensionsRootDir(project)
        val childDirectories = extensionRootDir.listFiles(FileFilter { it.isDirectory })
        return if (childDirectories == null || childDirectories.size < 2) {
            project.logger.lifecycle("using a single custom gradle distribution")
            emptyList()
        } else {
            childDirectories.map { it.name }.also {
                project.logger.lifecycle("using ${it.size} custom gradle distributions: $it")
            }
        }
    }

    private fun getExtensionsRootDir(project: Project): File {
        return project.file("src/main/resources/init.d")
    }

    private fun prepareCustomDistribution(
        distribution: String?,
        baseDistribution: File,
        project: Project,
        extension: CustomGradleDistExtension
    ) {
        val gradlePart = "gradle-${extension.gradleVersion.get()}"
        var customProjectPart = "${extension.customDistributionName.get()}-${extension.customDistributionVersion.get()}"
        if (distribution != null) {
            customProjectPart += "-$distribution"
        }
        val customDistributionFileName = "$gradlePart-${customProjectPart}.zip"
        val customDistributionsDir = getCustomDistributionsRootDir(project)
        val result = File(customDistributionsDir, customDistributionFileName)

        copyBaseDistribution(baseDistribution, result)
        addToZip(
            zip = result,
            distribution = distribution,
            project = project,
            gradleVersion = extension.gradleVersion.get(),
            pathsToExcludeFromContentExpansion = extension.skipContentExpansionFor.get()
        )
        project.logger.lifecycle("Prepared custom gradle distribution at $result.absolutePath")
    }

    private fun copyBaseDistribution(baseDistribution: File, customDistribution: File) {
        try {
            val from = FileInputStream(baseDistribution).channel
            val to = FileOutputStream(customDistribution).channel
            to.transferFrom(from, 0, Long.MAX_VALUE)
            from.close()
            to.close()
        } catch (e: Exception) {
            throw BuildException(
                "failed to copy base gradle distribution from ${baseDistribution.canonicalPath} "
                + "to ${customDistribution.canonicalPath}",
                e
            )
        }
    }

    private fun addToZip(
        zip: File,
        distribution: String?,
        project: Project,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Collection<String>
    ) {
        try {
            doAddToZip(
                zip = zip,
                distribution = distribution,
                project = project,
                gradleVersion = gradleVersion,
                pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion
            )
        } catch (e: Exception) {
            throw BuildException("failed to add entries to the custom gradle distribution ${zip.canonicalPath}", e)
        }
    }

    private fun doAddToZip(
        zip: File,
        distribution: String?,
        project: Project,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Collection<String>
    ) {
        val extensionsRootDir = getExtensionsRootDir(project)
        val zipFileSystem = FileSystems.newFileSystem(
            URI.create("jar:${zip.toPath().toUri()}"),
            mapOf("create" to "true")
        )
        addToZip(
            zip = zipFileSystem,
            includeRootDir = distribution?.let {
                File(extensionsRootDir, it)
            } ?: extensionsRootDir,
            project = project,
            gradleVersion = gradleVersion,
            pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion
        )
        zipFileSystem.close()
    }

    private fun addToZip(
        zip: FileSystem,
        includeRootDir: File,
        project: Project,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Collection<String>
    ) {
        addDirectoryToZip(
            zip = zip,
            includeRootDir = includeRootDir,
            directoryToInclude = includeRootDir,
            project = project,
            gradleVersion = gradleVersion,
            pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion
        )
    }

    private fun addDirectoryToZip(
        zip: FileSystem,
        includeRootDir: File,
        directoryToInclude: File,
        project: Project,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Collection<String>
    ) {
        directoryToInclude.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                addDirectoryToZip(
                    zip = zip,
                    includeRootDir = includeRootDir,
                    directoryToInclude = child,
                    project = project,
                    gradleVersion = gradleVersion,
                    pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion
                )
            } else {
                addFileToZip(
                    zip = zip,
                    includeRootDir = includeRootDir,
                    fileToInclude = child,
                    project = project,
                    gradleVersion = gradleVersion,
                    pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion
                )
            }
        }
    }

    private fun addFileToZip(
        zip: FileSystem,
        includeRootDir: File,
        fileToInclude: File,
        project: Project,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Collection<String>
    ) {
        val relativePath = fileToInclude.canonicalPath.substring(includeRootDir.canonicalPath.length)
        val to = zip.getPath("gradle-$gradleVersion/init.d/$relativePath")
        if (to.parent != null) {
            Files.createDirectories(to.parent)
        }

        val exclusionRule = pathsToExcludeFromContentExpansion.find {
            val ruleRoot = File(includeRootDir, it)
            fileToInclude.canonicalPath.startsWith(ruleRoot.canonicalPath)
        }
        if (exclusionRule == null) {
            Files.copy(applyTemplates(fileToInclude, project).toPath(), to)
        } else {
            project.logger.lifecycle(
                "skipped content expansion for file $relativePath because of exclusion rule '$exclusionRule'"
            )
            Files.copy(fileToInclude.toPath(), to)
        }

        project.logger.lifecycle("Added $fileToInclude.absolutePath to the custom gradle distribution")
    }

    private fun applyTemplates(file: File, project: Project): File {
        val includeRootDir = getIncludeRootDir(project)
        if (!includeRootDir.isDirectory) {
            project.logger.lifecycle(
                "skipped includes replacement - no 'include' directory is found at ${includeRootDir.canonicalPath}"
            )
            return file
        }
        val ongoingReplacements = Stack<String>()
        val includesRef = mutableListOf<MutableMap<String, String?>>()
        val includes = mutableMapOf<String, String?>().withDefault { replacement ->
            if (ongoingReplacements.contains(replacement)) {
                val error = buildString {
                    append("can not create custom Gradle distribution - detected a cyclic text expansion sequence:\n")
                    val replacementsList = ongoingReplacements.toMutableList()
                    replacementsList += replacement
                    replacementsList.forEachIndexed { i, ongoingReplacement ->
                        if (i == 0) {
                            this.append("'$ongoingReplacement' (${file.canonicalPath})")
                        } else {
                            val location = File(includeRootDir, "${replacementsList[i - 1]}.gradle")
                            this.append("\n  |\n")
                            this.append("'$ongoingReplacement' (${location.canonicalPath})")
                        }
                    }
                }
                throw IllegalStateException(error)
            }
            var include = File(includeRootDir, "${replacement}.gradle")
            if (!include.isFile) {
                include = File(includeRootDir, "${replacement}.gradle.kts")
            }
            if (include.isFile) {
                ongoingReplacements.push(replacement)
                val result = expand(
                    project = project,
                    file = file,
                    includes = includesRef[0],
                    text = include.readText()
                )
                ongoingReplacements.pop()
                result
            } else {
                null
            }
        }
        includesRef += includes

        val expandedText = expand(
            project = project,
            file = file,
            includes = includes,
            text = file.readText()
        )

        val result = Files.createTempFile("", "${file.name}.tmp").toFile()
        result.writeText(expandedText)
        return result
    }

    private fun getIncludeRootDir(project: Project): File {
        return project.file("src/main/resources/include")
    }

    private fun expand(project: Project, file: File, includes: Map<String, String?>, text: String): String  {
        var match = PATTERN.find(text)
        var start = 0
        val buffer = StringBuilder()
        while (match != null) {
            val replaceFrom = match.groupValues[1]
            val replaceTo = includes.getValue(replaceFrom)
            if (replaceTo == null) {
                match = match.next()
                continue
            }

            buffer.append(text.substring(start, match.range.first))
            val indent = getIndent(text, match.range.first)
            buffer.append(indentText(replaceTo.toString(), indent))
            start = match.range.last + 1
            project.logger.lifecycle("applied replacement '$replaceFrom' to the ${file.canonicalPath}")
            match = match.next()
        }
        if (start > 0) {
            buffer.append(text.substring(start))
        }

        return if (buffer.isEmpty()) {
            text
        } else {
            buffer.toString()
        }
    }

    private fun getIndent(text: String, offset: Int): Int {
        var result = 0
        var currentOffset = offset
        while (--currentOffset > 0) {
            if (text[currentOffset] == '\n') {
                break
            }
            result++
        }
        return result
    }

    private fun indentText(text: String, indent: Int): String {
        val indentString = " ".repeat(indent)
        return buildString {
            var firstLine = true
            for (line in text.lines()) {
                if (!firstLine) {
                    append("\n")
                }
                if (!firstLine && line.isNotBlank()) {
                    append(indentString)
                }
                append(line)
                firstLine = false
            }
        }
    }

    companion object {
        private val PATTERN = """\$(\S+)\$""".toRegex()
    }
}