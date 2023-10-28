package tech.harmonysoft.oss.gradle.dist

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Stack
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import tech.harmonysoft.oss.test.util.TestUtil.fail

class CustomGradleDistributionPluginTest {

    @Test
    fun `when client project has a single distribution and no templates then it is correctly packaged`() {
        doTest("single-distribution-no-templates")
    }

    @Test
    fun `when client project has a single distribution and single template then it is correctly packaged`() {
        doTest("single-distribution-single-template")
    }

    @Test
    fun `when client project has a single distribution and multiple templates then it is correctly packaged`() {
        doTest("single-distribution-multiple-templates")
    }

    @Test
    fun `when inadvertent expansion syntax is detected then it is left as-is`() {
        doTest("no-unnecessary-expansion")
    }

    @Test
    fun `when nested expansion is configured then it is correctly expanded`() {
        doTest("nested-expansion")
    }

    @Test
    fun `when multiple distributions are configured then multiple distributions are created`() {
        doTest("multiple-distributions")
    }

    @Test
    fun `when cyclic expansion chain is detected then it is reported`() {
        try {
            doTest("cyclic-expansion")
            fail(
                "expected to get an exception with problem details when cyclic " +
                "replacements chain is detected - 'detected a cyclic text expansion sequence'"
            )
        } catch (e: Exception) {
            assertThat(e.message)
                .describedAs(
                    "expected to get an exception with problem details when cyclic " +
                    "replacements chain is detected - 'detected a cyclic text expansion sequence'"
                )
                .contains("detected a cyclic text expansion sequence")
        }
    }

    @Test
    fun `when there is an existing build task then the plugin attaches to it`() {
        doTest("existing-build-task", """
            plugins {
                java
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                customDistributionVersion = "$PROJECT_VERSION"
                customDistributionName = "$PROJECT_NAME"
            }
        """.trimIndent())
    }

    @Test
    fun `when there is an exclusion rule for a nested path then it is respected`() {
        doTest("expansion-filter-nested-path", """
            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                customDistributionVersion = "$PROJECT_VERSION"
                customDistributionName = "$PROJECT_NAME"
                skipContentExpansionFor = listOf("bin")
            }
        """.trimIndent())
    }

    @Test
    fun `when there is an exclusion rule for a root path then it is respected`() {
        doTest("expansion-filter-root-path", """
            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                customDistributionVersion = "$PROJECT_VERSION"
                customDistributionName = "$PROJECT_NAME"
                skipContentExpansionFor = listOf(".")
            }
        """.trimIndent())
    }

    private fun doTest(testName: String) {
        doTest(testName, BUILD_TEMPLATE)
    }

    private fun doTest(testName: String, buildGradleContent: String) {
        val testFiles = prepareInput(testName, buildGradleContent)
        GradleRunner.create()
            .withProjectDir(testFiles.inputRootDir)
            .withArguments("build", "--stacktrace")
            .withPluginClasspath()
            .withDebug(true)
            .build()

        verify(testFiles.expectedRootDir, File(testFiles.inputRootDir, "build/gradle-dist"))
    }

    private fun prepareInput(testDirName: String, buildGradleContent: String): TestFiles {
        val testRootResourcePath = "/$testDirName"
        val testRootResource = this::class.java.getResource(testRootResourcePath) ?: fail(
            "can't find test resource at path '$testRootResourcePath'"
        )
        val testRoot = File(testRootResource.file)
        val inputRootDir = copy(File(testRoot, "input"))
        createGradleFile(inputRootDir, buildGradleContent)
        createGradleDistributionZip(inputRootDir)
        return TestFiles(inputRootDir, File(testRoot, "expected"))
    }

    private fun copy(dir: File): File {
        val result = Files.createTempDirectory("${dir.name}-tmp").toFile().apply {
            deleteOnExit()
        }
        val resourcesRoot = File(result, "src/main/resources")
        Files.createDirectories(resourcesRoot.toPath())
        dir.listFiles()?.forEach { child ->
            copy(child, resourcesRoot)
        }
        return result
    }

    private fun copy(toCopy: File, destinationDir: File) {
        if (toCopy.isFile) {
            Files.copy(toCopy.toPath(), File(destinationDir, toCopy.name).toPath())
        } else {
            toCopy.listFiles()?.let { children ->
                val dir = File(destinationDir, toCopy.name)
                Files.createDirectories(dir.toPath())
                for (child in children) {
                    copy(child, dir)
                }
            }
        }
    }

    private fun createGradleFile(projectRootDir: File, content: String) {
        File(projectRootDir, "build.gradle.kts").writeText(content)
    }

    private fun createGradleDistributionZip(projectRootDir: File) {
        val downloadDir = File(projectRootDir, "build/download")
        Files.createDirectories(downloadDir.toPath())
        val zip = File(downloadDir, "gradle-${GRADLE_VERSION}-bin.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream (zip))).use {
            val entry = ZipEntry("gradle-$GRADLE_VERSION/")
            it.putNextEntry(entry)
            it.closeEntry()
        }
    }

    private fun verify(expectedRootDir: File, actualDistDir: File) {
        val expectedDistributions = expectedRootDir.listFiles { file -> file.isDirectory } ?: fail(
            "no child directories are found in 'expected' directory '${expectedRootDir.canonicalPath}'"
        )
        if (expectedDistributions.size < 2) {
            assertThat(actualDistDir.list()).hasSize(1)
            verifyContentTheSame(
                File(expectedRootDir, "init.d").toPath(),
                unzip(actualDistDir, null).toPath()
            )
        } else {
            for (expectedDistributionRootDir in expectedDistributions) {
                verifyContentTheSame(
                    File(expectedDistributionRootDir, "init.d").toPath(),
                    unzip(actualDistDir, expectedDistributionRootDir.name).toPath()
                )
            }
        }
    }

    private fun verifyContentTheSame(expectedRoot: Path, actualRoot: Path) {
        val keyExtractor: (Path) -> String = { path ->
            val pathAsString = path.toString()
            val i = pathAsString.indexOf("init.d")
            pathAsString.substring(i + "init.d/".length)
        }

        val allExpected = listFiles(expectedRoot).associateBy(keyExtractor)
        val allActual = listFiles(actualRoot).associateBy(keyExtractor)

        if (allExpected.size != allActual.size) {
            val unexpected = allActual.keys.toMutableSet()
            unexpected.removeAll(allActual.keys)
            if (unexpected.isNotEmpty()) {
                fail("Unexpected entries are found in the custom distribution's 'init.d' directory: $unexpected")
            }

            val unmatched = allExpected.keys.toMutableSet()
            unmatched.removeAll(allActual.keys)
            if (unmatched.isNotEmpty()) {
                fail("Expected entries are not found in the custom distribution's 'init.d' directory: $unexpected")
            }
        }

        for ((pathKey, path) in allExpected) {
            val actualPath = allActual[pathKey] ?: fail(
                "expected to find '$pathKey' in the custom distribution's 'init.d' directory but it's not there"
            )
            val expectedFile = path.toFile()
            if (expectedFile.isFile) {
                val expectedText = expectedFile.readText()
                val actualText = actualPath.toFile().readText()
                assertThat(actualText)
                    .describedAs("text mismatch in $path")
                    .isEqualTo(expectedText)
            }
        }
    }

    private fun unzip(parentDir: File, distribution: String?): File {
        val zipName = buildString {
            append("gradle-$GRADLE_VERSION-$PROJECT_NAME-$PROJECT_VERSION")
            if (distribution != null) {
                append("-$distribution")
            }
            append(".zip")
        }
        val zip = File(parentDir, zipName)
        assertThat(zip.isFile)
            .describedAs("expected to find a custom distribution at $zip.absolutePath but it's not there")
            .isTrue()
        val rootUnzipDir = Files.createTempDirectory(zipName).toFile()
        val zipFile = ZipFile(zip)
        for (zipEntry in zipFile.entries()) {
            val path = File(rootUnzipDir, zipEntry.name).toPath()
            if (zipEntry.isDirectory){
                Files.createDirectories(path)
            }
            else {
                val p = path.parent
                if (!Files.exists(p)) {
                    Files.createDirectories(p)
                }
                Files.copy(zipFile.getInputStream(zipEntry), path)
            }
        }
        val result = File(rootUnzipDir, "gradle-$GRADLE_VERSION/init.d")
        assertThat(result.isDirectory)
            .describedAs("expected to find custom distribution content at $result.absolutePath but it's not there")
            .isTrue()
        return result
    }

    private fun listFiles(path: Path): List<Path> {
        val result = mutableListOf<Path>()
        val toProcess = Stack<Path>()
        toProcess.push(path)
        while (!toProcess.isEmpty()) {
            val p = toProcess.pop()
            if (p.toFile().isDirectory()) {
                Files.list(p)?.forEach {
                    toProcess.push(it)
                }
            } else {
                result.add(p)
            }
        }
        return result
    }

    data class TestFiles(
        val inputRootDir: File,
        val expectedRootDir: File
    )

    companion object {

        const val GRADLE_VERSION = "8.3"
        const val PROJECT_NAME = "my-project"
        const val PROJECT_VERSION = "1.0"

        val BUILD_TEMPLATE = """
            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                customDistributionVersion = "$PROJECT_VERSION"
                customDistributionName = "$PROJECT_NAME"
            }
        """.trimIndent()
    }
}