package tech.harmonysoft.oss.gradle.dist

import org.gradle.testkit.runner.GradleRunner
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Stream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

import static java.util.function.Function.identity
import static java.util.stream.Collectors.toMap
import static org.junit.Assert.*

class CustomGradleDistributionPluginTest {

    private static final String GRADLE_VERSION = '4.10'
    private static final String PROJECT_NAME = 'my-project'
    private static final String PROJECT_VERSION = '1.0'

    private static final BUILD_TEMPLATE = """
plugins {
    id 'tech.harmonysoft.oss.custom-gradle-dist-plugin'
}

gradleDist {
    gradleVersion = '$GRADLE_VERSION'
    customDistributionVersion = '$PROJECT_VERSION'
    customDistributionName = '$PROJECT_NAME'
}
"""

    @Test
    void 'when client project has a single distribution and no templates then it is correctly packaged'() {
        doTest('single-distribution-no-templates')
    }

    @Test
    void 'when client project has a single distribution and single template then it is correctly packaged'() {
        doTest('single-distribution-single-template')
    }

    @Test
    void 'when client project has a single distribution and multiple templates then it is correctly packaged'() {
        doTest('single-distribution-multiple-templates')
    }

    @Test
    void 'when inadvertent expansion syntax is detected then it is left as-is'() {
        doTest('no-unnecessary-expansion')
    }

    @Test
    void 'when nested expansion is configured then it is correctly expanded'() {
        doTest('nested-expansion')
    }

    @Test
    void 'when multiple distributions are configured then multiple distributions are created'() {
        doTest('multiple-distributions')
    }

    @Test
    void 'when client project has a single property, single distribution and single replacement then it is correctly packaged'() {
        doTest('property-single-replacement')
    }

    @Test
    void 'when client project has a single property, single distribution and multiple replacements then it is correctly packaged'() {
        doTest('property-multiple-replacements')
    }

    @Test
    void 'when client project has a single property and nested replacements then it is correctly packaged'() {
        doTest('property-nested-expansion')
    }

    @Test
    void 'when client project has multiple properties and multiple distributions then it is correctly packaged'() {
        doTest('property-multiple-distributions')
    }

    @Test
    void 'when a property and an include file have the same identifier then it is reported'() {
        def expectedError = 'duplicate identifier detected'
        def errorMessage = "Expected to get an exception with problem details when duplicate identifier " +
                "is detected - '$expectedError'"
        try {
            doTest('property-duplicate-identifier')
            fail(errorMessage)
        } catch (e) {
            assertTrue("$errorMessage. Got the following instead: '${e.message}'",
                    e.message.contains(expectedError))
        }
    }

    @Test
    void 'when cyclic expansion chain is detected then it is reported'() {
        def expectedError = 'detected a cyclic text expansion sequence'
        def errorMessage = "Expected to get an exception with problem details when cyclic replacements chain " +
                "is detected - '$expectedError'"
        try {
            doTest('cyclic-expansion')
            fail(errorMessage)
        } catch (e) {
            assertTrue("$errorMessage. Got the following instead: '${e.message}'",
                       e.message.contains(expectedError))
        }
    }

    @Test
    void 'when there is an existing build task then the plugin attaches to it'() {
        doTest('existing-build-task', """
plugins {
    id 'tech.harmonysoft.oss.custom-gradle-dist-plugin'
    id 'java'
}

gradleDist {
    gradleVersion = '$GRADLE_VERSION'
    customDistributionVersion = '$PROJECT_VERSION'
    customDistributionName = '$PROJECT_NAME'
}
""")
    }

    @Test
    void 'when there is an exclusion rule for a nested path then it is respected'() {
        doTest('expansion-filter-nested-path', """
plugins {
    id 'tech.harmonysoft.oss.custom-gradle-dist-plugin'
}

gradleDist {
    gradleVersion = '$GRADLE_VERSION'
    customDistributionVersion = '$PROJECT_VERSION'
    customDistributionName = '$PROJECT_NAME'
    skipContentExpansionFor = [
      'bin'
    ]
}
""")
    }

    @Test
    void 'when there is an exclusion rule for a root path then it is respected'() {
        doTest('expansion-filter-root-path', """
plugins {
    id 'tech.harmonysoft.oss.custom-gradle-dist-plugin'
}

gradleDist {
    gradleVersion = '$GRADLE_VERSION'
    customDistributionVersion = '$PROJECT_VERSION'
    customDistributionName = '$PROJECT_NAME'
    skipContentExpansionFor = [
      '.'
    ]
}
""")
    }

    private void doTest(String testName) {
        doTest(testName, BUILD_TEMPLATE)
    }

    private void doTest(String testName, String buildGradleContent) {
        def testFiles = prepareInput(testName, buildGradleContent)
        GradleRunner.create()
            .withProjectDir(testFiles.inputRootDir)
            .withArguments('build', '--stacktrace')
            .withPluginClasspath()
            .withDebug(true)
            .build()
        verify(testFiles.expectedRootDir, new File(testFiles.inputRootDir, 'build/gradle-dist'))
    }

    private TestFiles prepareInput(String testDirName, String buildGradleContent) {
        def testRoot = new File(getClass().getResource("/$testDirName").file)
        def inputRootDir = copy(new File(testRoot, 'input'))
        createGradleFile(inputRootDir, buildGradleContent)
        createGradleDistributionZip(inputRootDir)
        return new TestFiles(inputRootDir, new File(testRoot, 'expected'))
    }

    private static File copy(File dir) {
        def result = Files.createTempDirectory("${dir.name}-tmp").toFile()
        result.deleteOnExit()
        def resourcesRoot = new File(result, 'src/main/resources')
        Files.createDirectories(resourcesRoot.toPath())
        def children = dir.listFiles()
        for (child in children) {
            copy(child, resourcesRoot)
        }
        return result
    }

    private static void copy(File toCopy, File destinationDir) {
        if (toCopy.file) {
            Files.copy(toCopy.toPath(), new File(destinationDir, toCopy.name).toPath())
        } else {
            def children = toCopy.listFiles()
            if (children != null) {
                def dir = new File(destinationDir, toCopy.name)
                Files.createDirectories(dir.toPath())
                for (child in children) {
                    copy(child, dir)
                }
            }
        }
    }

    private static void createGradleFile(File projectRootDir, String content) {
        Files.write(new File(projectRootDir, 'build.gradle').toPath(), content.getBytes(StandardCharsets.UTF_8))
    }

    private static void createGradleDistributionZip(File projectRootDir) {
        def downloadDir = new File(projectRootDir, "build/download")
        Files.createDirectories(downloadDir.toPath())
        def zip = new File(downloadDir, "gradle-${GRADLE_VERSION}-bin.zip")
        new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zip))).withCloseable {
            def entry = new ZipEntry("gradle-$GRADLE_VERSION/")
            it.putNextEntry(entry)
            it.closeEntry()
        }
    }

    private static void verify(File expectedRootDir, File actualDistDir) {
        def filter = { it.directory } as FileFilter
        def expectedDistributions = expectedRootDir.listFiles(filter)
        if (expectedDistributions.length < 2) {
            assertEquals(actualDistDir.list().length, 1)
            verifyContentTheSame(
                new File(expectedRootDir, 'init.d').toPath(),
                unzip(actualDistDir, null).toPath()
            )
        } else {
            for (expectedDistributionRootDir in expectedDistributions) {
                verifyContentTheSame(
                    new File(expectedDistributionRootDir, 'init.d').toPath(),
                    unzip(actualDistDir, expectedDistributionRootDir.name).toPath()
                )
            }
        }
    }

    private static File unzip(File parentDir, String distribution) {
        def zipName = "gradle-$GRADLE_VERSION-$PROJECT_NAME-$PROJECT_VERSION"
        if (distribution != null) {
            zipName += "-$distribution"
        }
        zipName += '.zip'
        def zip = new  File(parentDir, zipName)
        assertTrue("Expected to find a custom distribution at $zip.absolutePath but it's not there", zip.file)
        def rootUnzipDir = Files.createTempDirectory(zipName).toFile()
        def zipFile = new ZipFile(zip)
        zipFile.entries().each { zipEntry ->
            def path = new File(rootUnzipDir, zipEntry.name).toPath()
            if(zipEntry.directory){
                Files.createDirectories(path)
            }
            else {
                def p = path.getParent()
                if (!Files.exists(p)) {
                    Files.createDirectories(p)
                }
                Files.copy(zipFile.getInputStream(zipEntry), path)
            }
        }
        def result = new File(rootUnzipDir, "gradle-$GRADLE_VERSION/init.d")
        assertTrue("Expected to find custom distribution content at $result.absolutePath but it's not there",
            result.directory)
        return result
    }

    private static void verifyContentTheSame(Path expectedRoot, Path actualRoot) {
        def keyExtractor = { Path path ->
            def pathAsString = path.toString()
            def i = pathAsString.indexOf('init.d')
            return pathAsString.substring(i + 'init.d/'.length())
        }
        Map<String, Path> allExpected = listFiles(expectedRoot).collect(toMap(keyExtractor, identity()))
        Map<String, Path> allActual = listFiles(actualRoot).collect(toMap(keyExtractor, identity()))

        if (allExpected.size() != allActual.size()) {
            def unexpected = new HashSet(allActual.keySet())
            unexpected.removeAll(allActual.keySet())
            if (!unexpected.empty) {
                fail("Unexpected entries are found in the custom distribution's 'init.d' directory: $unexpected")
            }

            def unmatched = new HashSet(allExpected.keySet())
            unmatched.removeAll(allActual.keySet())
            if (!unmatched.empty) {
                fail("Expected entries are not found in the custom distribution's 'init.d' directory: $unexpected")
            }
        }

        allExpected.each { pathKey, path ->
            if (!allActual.containsKey(pathKey)) {
                fail("Expected to find '$pathKey' in the custom distribution's 'init.d' directory but it's not there")
            }
            if (path.toFile().file) {
                def expectedText = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
                def actualText = new String(Files.readAllBytes(allActual.get(pathKey)), StandardCharsets.UTF_8)
                assertEquals("Text mismatch in $path", expectedText, actualText)
            }
        }
    }

    private static Stream<Path> listFiles(Path path) {
        List<Path> result = new ArrayList<>()
        Stack<Path> toProcess = new Stack<>()
        toProcess.push(path)
        while (!toProcess.isEmpty()) {
            Path p = toProcess.pop()
            if (p.toFile().isDirectory()) {
                Files.list(p).forEach {
                    toProcess.push(it)
                }
            } else {
                result.add(p)
            }
        }
        return result.stream()
    }

    private static class TestFiles {

        File inputRootDir
        File expectedRootDir

        TestFiles(File inputRootDir, File expectedRootDir) {
            this.inputRootDir = inputRootDir
            this.expectedRootDir = expectedRootDir
        }
    }
}
