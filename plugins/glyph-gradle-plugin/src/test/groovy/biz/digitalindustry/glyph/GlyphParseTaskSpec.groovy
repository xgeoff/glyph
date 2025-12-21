package biz.digitalindustry.glyph

import org.gradle.testkit.runner.GradleRunner
import spock.lang.Specification

import java.nio.file.Files
import java.nio.file.Path

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class GlyphParseTaskSpec extends Specification {

    Path testProjectDir
    Path grammarRoot = Path.of("../../grammar").toAbsolutePath().normalize()

    def setup() {
        testProjectDir = Files.createTempDirectory("glyph-parse-test")
        Files.createDirectories(testProjectDir.resolve("src/main/glyph"))
        Files.createDirectories(testProjectDir.resolve("grammar"))
    }

    def cleanup() {
        testProjectDir?.toFile()?.deleteDir()
    }

    def "glyphParse task parses glyph sources with shared grammar"() {
        given: "a minimal build that applies the plugin and includes glyph + grammar files"
        def buildFile = testProjectDir.resolve("build.gradle")
        def settingsFile = testProjectDir.resolve("settings.gradle")

        buildFile.toFile().text = """
            plugins {
                id 'biz.digitalindustry.glyph'
            }
            repositories { mavenCentral(); gradlePluginPortal() }
        """.stripIndent()

        settingsFile.toFile().text = "rootProject.name = 'glyph-parse-fixture'"

        def sourceFile = testProjectDir.resolve("src/main/glyph/main.gly")
        sourceFile.toFile().text = '''
fun void main() {
  val int x = 2 + 3
  print("result:")
  print(x)
}
'''.stripIndent()

        def grammarSource = grammarRoot.resolve("glyph.peg")
        assert grammarSource.toFile().exists() : "Expected shared grammar at ${grammarSource}"
        Files.copy(grammarSource, testProjectDir.resolve("grammar/glyph.peg"))

        when: "running glyphParse"
        def result = GradleRunner.create()
                .withProjectDir(testProjectDir.toFile())
                .withArguments("glyphParse", "--stacktrace")
                .withPluginClasspath()
                .forwardOutput()
                .build()

        then:
        result.task(":glyphParse").outcome == SUCCESS
        result.output.contains("Parsed 1 function")
    }
}
