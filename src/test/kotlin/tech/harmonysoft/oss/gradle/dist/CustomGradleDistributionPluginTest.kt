package tech.harmonysoft.oss.gradle.dist

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import tech.harmonysoft.oss.test.util.TestUtil.fail
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
            doTest("cyclic-expansion-in-include-files")
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

    @Test
    fun `when custom single gradle distribution is built with default distribution type then it contains base distribution type`() {
        val testFiles = doTest("single-distribution-no-templates")
        val expectedCustomDistributionFile = File(
            testFiles.inputRootDir,
            "build/gradle-dist/gradle-$GRADLE_VERSION-$PROJECT_NAME-$PROJECT_VERSION-bin.zip"
        )
        verifyDistributionFile(expectedCustomDistributionFile)
        assertThat(expectedCustomDistributionFile).exists()
    }

    @Test
    fun `when custom single gradle distribution is built with non-default distribution type then it contains base distribution type`() {
        val testFiles = doTest("single-distribution-no-templates", """
            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                gradleDistributionType = "all"
                customDistributionVersion = "$PROJECT_VERSION"
                customDistributionName = "$PROJECT_NAME"
            }
        """.trimIndent())
        val expectedCustomDistributionFile = File(
            testFiles.inputRootDir,
            "build/gradle-dist/gradle-$GRADLE_VERSION-$PROJECT_NAME-$PROJECT_VERSION-all.zip"
        )
        verifyDistributionFile(expectedCustomDistributionFile)
    }

    private fun verifyDistributionFile(expectedCustomDistributionFile: File) {
        if (!expectedCustomDistributionFile.isFile) {
            fail(
                "expected custom distribution file ${expectedCustomDistributionFile.name} is not found at "
                + "${expectedCustomDistributionFile.canonicalPath}, available file(s): "
                + expectedCustomDistributionFile.parentFile.listFiles()?.joinToString { it.name }
            )
        }
    }

    @Test
    fun `when multiple distributions are configured with non-default type then multiple distributions with correct names are created`() {
        val testFiles = doTest("multiple-distributions", """
            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                gradleDistributionType = "all"
                customDistributionVersion = "$PROJECT_VERSION"
                customDistributionName = "$PROJECT_NAME"
            }
        """.trimIndent())
        for (distribution in listOf("library", "service")) {
            val expectedCustomDistributionFile = File(
                testFiles.inputRootDir,
                "build/gradle-dist/gradle-$GRADLE_VERSION-$PROJECT_NAME-$PROJECT_VERSION-$distribution-all.zip"
            )
            verifyDistributionFile(expectedCustomDistributionFile)
        }
    }

    @Test
    fun `when custom distribution file name mapper is configured in gradle groovy script then it is respected`() {
        val testFiles = prepareInput("single-distribution-no-templates", """
            import tech.harmonysoft.oss.gradle.dist.config.CustomDistributionNameMapper

            plugins {
                id "tech.harmonysoft.oss.custom-gradle-dist-plugin"
            }
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                gradleDistributionType = "all"
                customDistributionVersion = "$PROJECT_VERSION"
                customDistributionFileNameMapper = { gradleVersion, customDistributionVersion, distributionType, distributionName ->
                    "${"$"}{distributionType}-custom-${"$"}{customDistributionVersion}-base-${"$"}{gradleVersion}.zip"
                } as CustomDistributionNameMapper
            }
        """.trimIndent(), "build.gradle")
        runAndVerify(testFiles)
        val expectedCustomDistributionFile = File(
            testFiles.inputRootDir,
            "build/gradle-dist/all-custom-${PROJECT_VERSION}-base-$GRADLE_VERSION.zip"
        )
        verifyDistributionName(expectedCustomDistributionFile)
    }

    @Test
    fun `when custom distribution file name mapper is configured in gradle kotlin script then it is respected`() {
        val testFiles = doTest("single-distribution-no-templates", """
            import tech.harmonysoft.oss.gradle.dist.config.CustomDistributionNameMapper
            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                gradleDistributionType = "all"
                customDistributionVersion = "$PROJECT_VERSION"
                customDistributionFileNameMapper = CustomDistributionNameMapper {
                  gradleVersion: String, customDistributionVersion: String, distributionType: String, distributionName: String? ->
                    "${"$"}{distributionType}-custom-${"$"}{customDistributionVersion}-base-${"$"}{gradleVersion}.zip"
                }
            }
        """.trimIndent())
        val expectedCustomDistributionFile = File(
            testFiles.inputRootDir,
            "build/gradle-dist/all-custom-${PROJECT_VERSION}-base-$GRADLE_VERSION.zip"
        )
        verifyDistributionFile(expectedCustomDistributionFile)
    }

    @Test
    fun `when customDistributionVersion is not defined then project version is used`() {
        val version = "1.2.3"
        val testFiles = doTest("single-distribution-no-templates", """
            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            version = "$version"
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                gradleDistributionType = "all"
                customDistributionName = "$PROJECT_NAME"
            }
        """.trimIndent())

        val expectedCustomDistributionFile = File(
                testFiles.inputRootDir,
                "build/gradle-dist/gradle-$GRADLE_VERSION-$PROJECT_NAME-$version-all.zip"
        )
        verifyDistributionFile(expectedCustomDistributionFile)
    }

    @Test
    fun `when customDistributionVersion is defined then it is used`() {
        val version = "1.2.3"
        val customDistributionVersion = "3.2.1"
        val testFiles = doTest("single-distribution-no-templates", """
            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            version = "$version"
            
            gradleDist {
                gradleVersion = "$GRADLE_VERSION"
                gradleDistributionType = "all"
                customDistributionName = "$PROJECT_NAME"
                customDistributionVersion = "$customDistributionVersion"
            }
        """.trimIndent())

        val expectedCustomDistributionFile = File(
                testFiles.inputRootDir,
                "build/gradle-dist/gradle-$GRADLE_VERSION-$PROJECT_NAME-$customDistributionVersion-all.zip"
        )
        verifyDistributionFile(expectedCustomDistributionFile)
    }

    @Test
    fun `when customDistributionVersion and project version are not defined then build fails`() {
        try {
            doTest("single-distribution-no-templates", """
                plugins {
                    id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
                }
                
                gradleDist {
                    gradleVersion = "$GRADLE_VERSION"
                    gradleDistributionType = "all"
                    customDistributionName = "$PROJECT_NAME"
                }
            """.trimIndent())
        } catch (e: Exception) {
            assertThat(e.message).contains("custom gradle distribution version is undefined. Please specify it like below or use project.version as default")
        }
    }

    @Test
    fun `when replacements file is defined then it's respected`() {
        doTest("only-replacements-file")
    }

    @Test
    fun `when duplicate expansion is defined in replacements file and include file then it's handled as expected`() {
        try {
            doTest("duplicate-expansion-key")
            fail("expected that duplicate expansion key is reported")
        } catch (e: Exception) {
            assertThat(e.message).contains("there is property 'repository' in replacements file")
            assertThat(e.message).contains(
                "and also there is an include file repository.gradle. Can't decide which one should be applied"
            )
        }
    }

    @Test
    fun `when nested expansions happen in replacements file properties and include files then it works correctly`() {
        doTest("cross-references-between-replacements-file-and-include-files")
    }

    @Test
    fun `when cycle is found in replacements file then it's reported`() {
        try {
            doTest("cyclic-expansion-key-in-replacements-file")
            fail("expected that cycle in replacements file is reported")
        } catch (e: Exception) {
            assertThat(e.message).contains("""
                'prop3' (property from file replacements.properties)
                  |
                'prop1' (property from file replacements.properties)
                  |
                'prop2' (property from file replacements.properties)
                  |
                'prop3' (property from file replacements.properties)
            """.trimIndent())
        }
    }

    @Test
    fun `when cycle is found in replacements file and include files then it's reported`() {
        try {
            doTest("cyclic-expansion-key-in-replacements-file-and-include-files")
            fail("expected that cycle in replacements file is reported")
        } catch (e: Exception) {
            assertThat(e.message).contains("""
                'prop1' (property from file replacements.properties)
                  |
                'file1' (include file file1.gradle)
                  |
                'file2' (include file file2.gradle)
                  |
                'prop1' (property from file replacements.properties)
            """.trimIndent())
        }
    }

    @Test
    fun `when custom base gradle distribution mapper is defined in gradle groovy script then it's respected`() {
        val customBaseGradleDistFile = Files.createTempFile("base-gradle-dist", "").toFile()
        createGradleDistributionZip(customBaseGradleDistFile)
        val testFiles = prepareInput("single-distribution-no-templates","""
            import tech.harmonysoft.oss.gradle.dist.config.GradleUrlMapper

            plugins {
                id "tech.harmonysoft.oss.custom-gradle-dist-plugin"
            }
            
            gradleDist {
              gradleVersion = "$GRADLE_VERSION"
              customDistributionVersion = "$PROJECT_VERSION"
              customDistributionName = "$PROJECT_NAME"
              rootUrlMapper = { version, type ->
                  "file://${customBaseGradleDistFile.canonicalPath}"
              } as GradleUrlMapper
            }
        """.trimIndent(), "build.gradle")
        runAndVerify(testFiles)
    }

    @Test
    fun `when custom base gradle distribution mapper is defined in gradle kotlin script then it's respected`() {
        val customBaseGradleDistFile = Files.createTempFile("base-gradle-dist", "").toFile()
        createGradleDistributionZip(customBaseGradleDistFile)
        val testFiles = prepareInput("single-distribution-no-templates", """
            import tech.harmonysoft.oss.gradle.dist.config.GradleUrlMapper

            plugins {
                id("tech.harmonysoft.oss.custom-gradle-dist-plugin")
            }
            
            gradleDist {
              gradleVersion = "$GRADLE_VERSION"
              customDistributionVersion = "$PROJECT_VERSION"
              customDistributionName = "$PROJECT_NAME"
              rootUrlMapper = GradleUrlMapper { version: String, type: String ->
                  "file://${customBaseGradleDistFile.canonicalPath}"
              }
            }
        """.trimIndent())
        runAndVerify(testFiles)
    }

    private fun doTest(testName: String): TestFiles {
        return doTest(testName, BUILD_TEMPLATE)
    }

    private fun doTest(testName: String, buildGradleContent: String): TestFiles {
        val testFiles = prepareInput(testName, buildGradleContent)
        runAndVerify(testFiles)
        return testFiles
    }

    private fun prepareInput(
        testDirName: String,
        buildGradleContent: String,
        buildGradleFileName: String = "build.gradle.kts"
    ): TestFiles {
        val testRootResourcePath = "/$testDirName"
        val testRootResource = this::class.java.getResource(testRootResourcePath) ?: fail(
            "can't find test resource at path '$testRootResourcePath'"
        )
        val testRoot = File(testRootResource.file)
        val inputRootDir = copy(File(testRoot, "input"))
        createGradleFile(inputRootDir, buildGradleFileName, buildGradleContent)
        prepareGradleDistributionZip(inputRootDir)
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

    private fun createGradleFile(projectRootDir: File, buildGradleFileName: String, content: String) {
        val file = File(projectRootDir, buildGradleFileName)
        if (!file.exists()) {
            file.createNewFile()
        }
        file.writeText(content)
    }

    private fun prepareGradleDistributionZip(projectRootDir: File) {
        val downloadDir = File(projectRootDir, "build/download")
        Files.createDirectories(downloadDir.toPath())
        val zip = File(downloadDir, "gradle-${GRADLE_VERSION}-bin.zip")
        createGradleDistributionZip(zip)
    }

    private fun createGradleDistributionZip(zip: File) {
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
        val zips = parentDir.listFiles()?.filter { file ->
            distribution?.let { file.name.contains("-$it") } ?: true
            && file.name.endsWith(".zip")
        } ?: fail(
            "no zip files are found in the build dir ${parentDir.canonicalPath}"
        )
        if (zips.size != 1) {
            fail(
                "expected to find one custom gradle distribution zip file in ${parentDir.canonicalPath} "
                + "but found ${zips.size}: ${zips.joinToString { it.name }}"
            )
        }
        val zip = zips.first()
        val rootUnzipDir = Files.createTempDirectory(zip.name).toFile()
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

    private fun runAndVerify(testFiles: TestFiles) {
        GradleRunner.create()
            .withProjectDir(testFiles.inputRootDir)
            .withArguments("buildGradleDist", "--stacktrace")
            .withPluginClasspath()
            .withDebug(true)
            .build()

        verify(testFiles.expectedRootDir, File(testFiles.inputRootDir, "build/gradle-dist"))
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