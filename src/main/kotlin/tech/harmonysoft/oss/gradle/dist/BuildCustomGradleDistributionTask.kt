package tech.harmonysoft.oss.gradle.dist

import java.io.File
import java.io.FileFilter
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URI
import java.nio.channels.Channels
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.Properties
import java.util.Stack
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.tooling.BuildException
import tech.harmonysoft.oss.gradle.dist.config.CustomGradleDistConfig
import javax.inject.Inject

@Suppress("LeakingThis")
abstract class BuildCustomGradleDistributionTask @Inject constructor(
    @get:Nested val config: CustomGradleDistConfig
) : DefaultTask() {

    @get:Internal
    abstract val gradleDownloadDir: DirectoryProperty

    @get:OutputDirectory
    abstract val customDistributionOutputDir: DirectoryProperty

    init {
        gradleDownloadDir.convention(
            project.layout.buildDirectory.dir("gradle-download")
        )
        customDistributionOutputDir.convention(
            project.layout.buildDirectory.dir("gradle-dist")
        )
    }

    @TaskAction
    fun build() {
        val baseDistribution = getBaseGradleDistribution(config)
        val customDistributionsDir = customDistributionOutputDir.get().asFile
        remove(customDistributionsDir)
        Files.createDirectories(customDistributionsDir.toPath())

        val replacements = prepareReplacements()
        val distributions = getDistributions()
        if (distributions.isEmpty()) {
            prepareCustomDistribution(
                distribution = null,
                baseDistribution = baseDistribution,
                extension = config,
                replacements = replacements
            )
        } else {
            for (distribution in distributions) {
                prepareCustomDistribution(
                    distribution = distribution,
                    baseDistribution = baseDistribution,
                    extension = config,
                    replacements = replacements
                )
            }
        }
    }

    private fun getBaseGradleDistribution(extension: CustomGradleDistConfig): File {
        return try {
            doGetBaseGradleDistribution(extension)
        } catch (e: Exception) {
            throw BuildException(
                "failed to prepare base gradle distribution of version ${extension.gradleVersion.get()}",
                e
            )
        }
    }

    private fun doGetBaseGradleDistribution(extension: CustomGradleDistConfig): File {
        val gradleBaseName = "gradle-${extension.gradleVersion.get()}"
        val gradleZip = "$gradleBaseName-${extension.gradleDistributionType.get()}.zip"
        val baseGradleArchive = gradleDownloadDir.map { it.file(gradleZip) }.get().asFile
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
                fromUrl = extension.rootUrlMapper.get().map(
                    extension.gradleVersion.get(),
                    extension.gradleDistributionType.get()
                ).toString(),
                toFile = File(archiveDir, gradleZip)
            )
        }
        return baseGradleArchive
    }

    private fun download(fromUrl: String, toFile: File) {
        val from = Channels.newChannel(URI(fromUrl).toURL().openStream())
        project.logger.lifecycle("about to download a gradle distribution from $fromUrl to ${toFile.canonicalPath}")
        FileOutputStream(toFile).channel.use {
            it.transferFrom(from, 0, Long.MAX_VALUE)
        }
        project.logger.lifecycle("downloaded a gradle distribution from $fromUrl to ${toFile.canonicalPath}")
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

    private fun getDistributions(): Collection<String> {
        val childDirectories = config.initScriptsSourceDir.get().asFile.listFiles(FileFilter { it.isDirectory })
        return if (childDirectories == null || childDirectories.size < 2) {
            project.logger.lifecycle("using a single custom gradle distribution")
            emptyList()
        } else {
            childDirectories.map { it.name }.also {
                project.logger.lifecycle("using ${it.size} custom gradle distributions: $it")
            }
        }
    }

    private fun prepareReplacements(): Map<String, String> {
        val replacementPropertiesFile = project.file("src/main/resources/include/$REPLACEMENTS_FILE_NAME")
        val propertyReplacements = loadReplacementsFromProperties(replacementPropertiesFile)
        val fileReplacements = loadReplacementsFromFiles()
        verifyNoDuplicateReplacements(
            fromPropertiesKeys = propertyReplacements.keys,
            propertiesFile = replacementPropertiesFile,
            fromFiles = fileReplacements
        )
        return expandReplacements(propertyReplacements + fileReplacements)
    }

    private fun loadReplacementsFromProperties(file: File): Map<String, RichValue> {
        if (!file.isFile) {
            project.logger.lifecycle("no replacements file is found in ${file.canonicalPath}")
            return emptyMap()
        }

        return Properties().apply {
            load(file.reader())
        }.map { it.key.toString() to RichValue(
            value = it.value.toString(),
            description = "property from file ${file.name}"
        )}.toMap()
    }

    private fun loadReplacementsFromFiles(): Map<String, RichValue> {
        return config.utilityScriptsSourceDir
            .orNull
            ?.asFile
            ?.listFiles()
            ?.filter {
                it.name != REPLACEMENTS_FILE_NAME
            }?.associate { file ->
                val endIndex = file.name.indexOf('.').takeIf { it > 0 } ?: file.name.length
                val key = file.name.substring(0, endIndex)
                key to RichValue(file.readText(), "include file ${file.name}")
            } ?: emptyMap()
    }

    private fun verifyNoDuplicateReplacements(
        fromPropertiesKeys: Set<String>,
        propertiesFile: File,
        fromFiles: Map<String, RichValue>
    ) {
        for (key in fromPropertiesKeys) {
            fromFiles[key]?.let {
                throw IllegalStateException(
                    "there is property '$key' in replacements file ${propertiesFile.canonicalPath} and also "
                    + "there is an ${it.description}. Can't decide which one should be applied"
                )
            }
        }
    }

    private fun expandReplacements(replacements: Map<String, RichValue>): Map<String, String> {
        val context = TemplateExpansionContext(replacements)
        for ((key, value) in context.rawReplacements) {
            expand(key, value, context)
        }
        return context.parsedReplacements
    }

    private fun expand(key: String, value: RichValue, context: TemplateExpansionContext): String {
        if (context.ongoingReplacements.any { it.first == key }) {
            val error = buildString {
                append("can not create custom Gradle distribution - detected a cyclic text expansion sequence:\n")
                val replacementsList = context.ongoingReplacements
                replacementsList.forEachIndexed { i, ongoingReplacement ->
                    if (i > 0) {
                        this.append("\n  |\n")
                    }
                    this.append("'${ongoingReplacement.first}' (${ongoingReplacement.second.description})")
                }
                this.append("\n  |\n")
                this.append("'$key' (${value.description})")
            }
            throw IllegalStateException(error)
        }
        context.ongoingReplacements.push(key to value)
        return try {
            expand(value) { nestedKey ->
                context.parsedReplacements[nestedKey] ?: context.rawReplacements[nestedKey]?.let {
                    expand(nestedKey, it, context)
                }
            }.also {  expandedValue ->
                context.parsedReplacements[key] = expandedValue
            }
        } finally {
            context.ongoingReplacements.pop()
        }
    }

    private fun expand(value: RichValue, nestedValueProvider: (String) -> String?): String {
        var match = PATTERN.find(value.value)
        var start = 0
        val buffer = StringBuilder()
        while (match != null) {
            val replaceFrom = match.groupValues[1]
            val replaceTo = nestedValueProvider(replaceFrom)
            if (replaceTo == null) {
                project.logger.lifecycle("can not expand meta-value '$replaceFrom' encountered in ${value.description}")
                match = match.next()
                continue
            }

            buffer.append(value.value.substring(start, match.range.first))
            val indent = getIndent(value.value, match.range.first)
            buffer.append(indentText(replaceTo.toString(), indent))
            start = match.range.last + 1
            project.logger.lifecycle("applied replacement '$replaceFrom' to ${value.description}")
            match = match.next()
        }
        if (start > 0) {
            buffer.append(value.value.substring(start))
        }

        return if (buffer.isEmpty()) {
            value.value
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

    private fun prepareCustomDistribution(
        distribution: String?,
        baseDistribution: File,
        extension: CustomGradleDistConfig,
        replacements: Map<String, String>
    ) {
        val customDistributionFileName = extension.customDistributionFileNameMapper.get().map(
            gradleVersion = extension.gradleVersion.get(),
            customDistributionVersion = extension.customDistributionVersion.get(),
            gradleDistributionType = extension.gradleDistributionType.get(),
            distributionName = distribution
        ).toString()
        val customDistributionsDir = customDistributionOutputDir.get().asFile
        val result = File(customDistributionsDir, customDistributionFileName)

        copyBaseDistribution(baseDistribution, result)
        addToZip(
            zip = result,
            distribution = distribution,
            gradleVersion = extension.gradleVersion.get(),
            pathsToExcludeFromContentExpansion = extension.skipContentExpansionFor.get().toSet(),
            replacements = replacements
        )
        project.logger.lifecycle("prepared custom gradle distribution at ${result.absolutePath}")
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
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Set<String>,
        replacements: Map<String, String>
    ) {
        try {
            doAddToZip(
                zip = zip,
                distribution = distribution,
                gradleVersion = gradleVersion,
                pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion,
                replacements = replacements
            )
        } catch (e: Exception) {
            throw BuildException("failed to add entries to the custom gradle distribution ${zip.canonicalPath}", e)
        }
    }

    private fun doAddToZip(
        zip: File,
        distribution: String?,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Set<String>,
        replacements: Map<String, String>
    ) {
        FileSystems.newFileSystem(
            URI.create("jar:${zip.toPath().toUri()}"),
            mapOf("create" to "true")
        )
        .use { zipFileSystem ->
            config.initScriptsSourceDir.get().asFile.let { initScriptsSourceDir ->
                addToZip(
                    zip = zipFileSystem,
                    includeRootDir = distribution?.let {
                        File(initScriptsSourceDir, it)
                    } ?: initScriptsSourceDir,
                    gradleVersion = gradleVersion,
                    pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion,
                    replacements = replacements
                )
            }
        }
    }

    private fun addToZip(
        zip: FileSystem,
        includeRootDir: File,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Set<String>,
        replacements: Map<String, String>
    ) {
        addDirectoryToZip(
            zip = zip,
            includeRootDir = includeRootDir,
            directoryToInclude = includeRootDir,
            gradleVersion = gradleVersion,
            pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion,
            replacements = replacements
        )
    }

    private fun addDirectoryToZip(
        zip: FileSystem,
        includeRootDir: File,
        directoryToInclude: File,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Set<String>,
        replacements: Map<String, String>
    ) {
        directoryToInclude.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                addDirectoryToZip(
                    zip = zip,
                    includeRootDir = includeRootDir,
                    directoryToInclude = child,
                    gradleVersion = gradleVersion,
                    pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion,
                    replacements = replacements
                )
            } else {
                addFileToZip(
                    zip = zip,
                    includeRootDir = includeRootDir,
                    fileToInclude = child,
                    gradleVersion = gradleVersion,
                    pathsToExcludeFromContentExpansion = pathsToExcludeFromContentExpansion,
                    replacements = replacements
                )
            }
        }
    }

    private fun addFileToZip(
        zip: FileSystem,
        includeRootDir: File,
        fileToInclude: File,
        gradleVersion: String,
        pathsToExcludeFromContentExpansion: Set<String>,
        replacements: Map<String, String>
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
            val tempFile = Files.createTempFile("", "${fileToInclude.name}.tmp").toFile()
            try {
                val expandedContent = expand(RichValue(
                    value = fileToInclude.readText(),
                    description = "file ${fileToInclude.name}"
                )) {
                    replacements[it]
                }
                tempFile.writeText(expandedContent)
                Files.copy(tempFile.toPath(), to)
            } finally {
                tempFile.delete()
            }
        } else {
            project.logger.lifecycle(
                "skipped content expansion for file $relativePath because of exclusion rule '$exclusionRule'"
            )
            Files.copy(fileToInclude.toPath(), to)
        }

        project.logger.lifecycle("added $fileToInclude.absolutePath to the custom gradle distribution")
    }

    private data class RichValue(
        val value: String,
        val description: String
    )

    private data class TemplateExpansionContext(val rawReplacements: Map<String, RichValue>) {
        val parsedReplacements = mutableMapOf<String, String>()
        val ongoingReplacements = Stack<Pair<String, RichValue>>()
    }

    companion object {
        private val PATTERN = """\$(\S+)\$""".toRegex()
        private const val REPLACEMENTS_FILE_NAME = "replacements.properties"
    }
}