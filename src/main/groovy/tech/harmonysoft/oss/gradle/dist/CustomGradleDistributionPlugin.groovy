package tech.harmonysoft.oss.gradle.dist


import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.tooling.BuildException

import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.regex.Pattern

class CustomGradleDistExtension {
    String gradleVersion
    String customDistributionName
    String customDistributionVersion
    String gradleDistributionType = 'bin'
}

class CustomGradleDistributionPlugin implements Plugin<Project> {

    private static final Pattern PATTERN = Pattern.compile(/\$([^\s]+)\$/)

    @Override
    void apply(Project project) {
        def extension = project.extensions.create('gradleDist', CustomGradleDistExtension)

        project.afterEvaluate {
            validate(extension)

            project.task('build').doLast {
                def baseDistribution = getBaseGradleDistribution(project, extension)

                def customDistributionsDir = getCustomDistributionsRootDir(project)
                remove(customDistributionsDir)
                Files.createDirectories(customDistributionsDir.toPath())

                def distributions = getDistributions(project)
                if (distributions.empty) {
                    prepareCustomDistribution(null, baseDistribution, project, extension)
                } else {
                    distributions.each { prepareCustomDistribution(it, baseDistribution, project, extension) }
                }
            }
        }
    }

    private static void validate(CustomGradleDistExtension extension) {
        if (extension.gradleVersion == null || extension.gradleVersion.trim().empty) {
            throw new IllegalStateException(
'''Can not build custom gradle distribution - base gradle version is undefined. Please specify it like below:

    gradleDist {
        gradleVersion = '4.10'
    }''')}

        if (extension.customDistributionVersion == null || extension.customDistributionVersion.trim().empty) {
            throw new IllegalStateException(
'''Can not build - custom gradle distribution version is undefined. Please specify it like below:

    gradleDist {
        customDistributionVersion = '1.0'
    }''')}

        if (extension.customDistributionName == null || extension.customDistributionName.trim().empty) {
            throw new IllegalStateException(
'''Can not build - custom gradle distribution project name is undefined. Please specify it like below:

    gradleDist {
        customDistributionName = 'my-project'
    }''')}

    }

    private static Collection<String> getDistributions(Project project) {
        def extensionRootDir = getExtensionsRootDir(project)
        def childDirectories = extensionRootDir.listFiles({ it.directory } as FileFilter)
        if (childDirectories == null || childDirectories.length == 0) {
            project.logger.lifecycle('Using a single custom gradle distribution')
            return []
        } else {
            def result = childDirectories.toList().collect { it.name }
            project.logger.lifecycle("Using ${result.size()} custom gradle distributions: $result")
            return result
        }
    }

    private static File getExtensionsRootDir(Project project) {
        return project.file('src/main/resources/init.d')
    }

    private static File getIncludeRootDir(Project project) {
        return project.file('src/main/resources/include')
    }

    private static File getCustomDistributionsRootDir(Project project) {
        return new File(project.buildDir, 'gradle-dist')
    }

    private static File getBaseGradleDistribution(Project project, CustomGradleDistExtension extension) {
        try {
            return doGetBaseGradleDistribution(project, extension)
        } catch (Exception e) {
            throw new BuildException("Failed to prepare base gradle distribution of version $extension.gradleVersion",
                    e)
        }
    }

    private static File doGetBaseGradleDistribution(Project project, CustomGradleDistExtension extension) {
        def gradleBaseName = "gradle-$extension.gradleVersion"
        def gradleZip = "$gradleBaseName-${extension.gradleDistributionType}.zip"
        def baseGradleArchive = new File(project.buildDir, "download/$gradleZip")
        if (!baseGradleArchive.file) {
            def archiveDir = baseGradleArchive.parentFile
            if (!archiveDir.directory) {
                boolean ok = archiveDir.mkdirs()
                if (!ok) {
                    throw new IllegalStateException("Can't create directory $archiveDir.absolutePath to store "
                            + "gradle distribution")
                }
            }
            download(project, gradleZip, archiveDir)
        }
        return baseGradleArchive
    }


    private static void download(Project project, String archiveName, File archiveDir) {
        def fromUrl = "https://services.gradle.org/distributions/$archiveName"
        def from = Channels.newChannel(new URL(fromUrl).openStream())
        def to = new File(archiveDir, archiveName)
        project.logger.lifecycle("About to download a gradle distribution from $fromUrl to $to.absolutePath")
        new FileOutputStream(to).withCloseable {
            it.channel.transferFrom(from, 0, Long.MAX_VALUE)
        }
        project.logger.lifecycle("Downloaded a gradle distribution from $fromUrl to $to.absolutePath")
    }

    private static void prepareCustomDistribution(String distribution,
                                                  File baseDistribution,
                                                  Project project,
                                                  CustomGradleDistExtension extension)
    {
        def gradlePart = "gradle-$extension.gradleVersion"
        def customProjectPart = "$extension.customDistributionName-$extension.customDistributionVersion"
        if (distribution != null) {
            customProjectPart += "-$distribution"
        }
        def customDistributionFileName = "$gradlePart-${customProjectPart}.zip"
        def customDistributionsDir = getCustomDistributionsRootDir(project)
        File result = new File(customDistributionsDir, customDistributionFileName)

        copyBaseDistribution(baseDistribution, result)
        addToZip(result, distribution, project, extension.gradleVersion)
        project.logger.lifecycle("Prepared custom gradle distribution at $result.absolutePath")
    }

    private static void copyBaseDistribution(File baseDistribution, File customDistribution) {
        try {
            def from = new FileInputStream(baseDistribution).channel
            def to = new FileOutputStream(customDistribution).channel
            to.transferFrom(from, 0, Long.MAX_VALUE)
        } catch (Exception e) {
            throw new BuildException("Failed to copy base gradle distribution from $baseDistribution.absolutePath "
                    + "to $customDistribution.absolutePath", e)
        }
    }

    private static void remove(File toRemove) {
        if (toRemove.directory) {
            def children = toRemove.listFiles()
            if (children != null) {
                children.each { remove(it) }
            }
            def ok = toRemove.deleteDir()
            if (!ok) {
                throw new IllegalStateException("Failed to remove directory $toRemove.absolutePath")
            }
        } else if (toRemove.exists()) {
            def ok = toRemove.delete()
            if (!ok) {
                throw new IllegalStateException("Failed to remove file $toRemove.absolutePath")
            }
        }
    }

    private static void addToZip(File zip, String distribution, Project project, String gradleVersion) {
        try {
            doAddToZip(zip, distribution, project, gradleVersion)
        } catch (Exception e) {
            throw new BuildException("Failed to add entries to the custom gradle distribution $zip.absolutePath", e)
        }
    }

    private static void doAddToZip(File zip, String distribution, Project project, String gradleVersion) {
        def extensionsRootDir = getExtensionsRootDir(project)
        def zipFileSystem = FileSystems.newFileSystem(URI.create("jar:${zip.toPath().toUri()}"), ['create': 'true'])
        if (distribution == null) {
            addToZip(zipFileSystem, extensionsRootDir, project, gradleVersion)
        } else {
            addToZip(zipFileSystem, new File(extensionsRootDir, distribution), project, gradleVersion)
        }
        zipFileSystem.close()
    }

    private static void addToZip(FileSystem zip, File includeRootDir, Project project, String gradleVersion) {
        addDirectoryToZip(zip, includeRootDir, includeRootDir, project, gradleVersion)
    }

    private static void addDirectoryToZip(FileSystem zip,
                                          File includeRootDir,
                                          File directoryToInclude,
                                          Project project,
                                          String gradleVersion)
    {
        def children = directoryToInclude.listFiles()
        for (File child : children ) {
            if (child.directory) {
                addDirectoryToZip(zip, includeRootDir, child, project, gradleVersion)
            } else {
                addFileToZip(zip, includeRootDir, child, project, gradleVersion)
            }
        }
    }

    private static void addFileToZip(FileSystem zip,
                                     File includeRootDir,
                                     File fileToInclude,
                                     Project project,
                                     String gradleVersion)
    {
        def relativePath = fileToInclude.absolutePath.substring(includeRootDir.absolutePath.length())
        def to = zip.getPath("gradle-$gradleVersion/init.d/$relativePath")
        if (to.parent != null) {
            Files.createDirectories(to.parent)
        }
        Files.copy(applyTemplates(fileToInclude, project).toPath(), to)
        project.logger.lifecycle("Added $fileToInclude.absolutePath to the custom gradle distribution")
    }

    private static File applyTemplates(File file, Project project) {
        def includeRootDir = getIncludeRootDir(project)
        if (!includeRootDir.directory) {
            project.logger.lifecycle("Skipped includes replacement - no 'include' directory is found "
                    + "at $includeRootDir.absolutePath")
            return file
        }
        def ongoingReplacements = new Stack<String>()
        List<Map<String, String>> includesRef = []
        def includes = new HashMap<String, String>().withDefault { replacement ->
            if (ongoingReplacements.contains(replacement)) {
                def buffer = new StringBuilder('Can not create custom Gradle distribution - detected a cyclic text ' +
                                                       'expansion sequence:\n')
                def replacementsList = ongoingReplacements.toList()
                replacementsList.add(replacement)
                replacementsList.eachWithIndex { ongoingReplacement, i ->
                    if (i == 0) {
                        buffer.append("'$ongoingReplacement' ($file.absolutePath)")
                    } else {
                        def location = new File(includeRootDir, "${replacementsList[i - 1]}.gradle")
                        buffer.append('\n  |\n').append("'$ongoingReplacement' ($location.absolutePath)")
                    }
                }
                throw new IllegalStateException(buffer.toString())
            }
            def include = new File(includeRootDir, "${replacement}.gradle")
            if (include.file) {
                ongoingReplacements.push(replacement)
                def result = expand(project,
                                    file,
                                    includesRef[0],
                                    new String(Files.readAllBytes(include.toPath()), StandardCharsets.UTF_8))
                ongoingReplacements.pop()
                return result
            } else {
                return null
            }
        }
        includesRef.add(includes)

        def text = new String(Files.readAllBytes(file.toPath()))
        def expandedText = expand(project, file, includes, text)

        def result = Files.createTempFile("", "${file.name}.tmp")
        return Files.write(result, expandedText.getBytes(StandardCharsets.UTF_8)).toFile()
    }

    private static String expand(Project project, File file, Map<String, String> includes, String text) {
        def matcher = PATTERN.matcher(text)
        int start = 0
        def buffer = new StringBuilder()
        while (matcher.find()) {
            def replaceFrom = matcher.group(1)
            def replaceTo = includes.get(replaceFrom)
            if (replaceTo == null) {
                continue
            }

            buffer.append(text.substring(start, matcher.start()))
            def indent = getIndent(text, matcher.start())
            buffer.append(indentText(replaceTo.toString(), indent))
            start = matcher.end()
            project.logger.lifecycle("Applied replacement '$replaceFrom' to the $file.absolutePath")
        }
        if (start > 0) {
            buffer.append(text.substring(start))
        }

        return buffer.length() <= 0 ? text : buffer.toString()
    }

    private static int getIndent(String text, int offset) {
        int result = 0
        while (--offset > 0) {
            if (text.charAt(offset) == '\n' as char) {
                break
            }
            result++
        }
        return result
    }

    private static String indentText(String text, int indent) {
        def indentString = " " * indent
        StringBuilder buffer = new StringBuilder()
        def firstLine = true
        text.eachLine {
            if (!firstLine) {
                buffer.append('\n')
            }
            if (!firstLine && !it.trim().isEmpty()) {
                buffer.append(indentString)
            }
            buffer.append(it)
            firstLine = false
        }
        return buffer.toString()
    }
}
