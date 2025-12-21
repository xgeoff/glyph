package biz.digitalindustry.glyph

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class GlyphPluginSpec extends Specification {

    def "plugin registers helloGlyph task"() {
        given:
        def project = ProjectBuilder.builder().build()

        when:
        project.pluginManager.apply 'biz.digitalindustry.glyph'

        then:
        project.tasks.findByName('helloGlyph') != null
        project.tasks.findByName('glyphParse') != null
        project.extensions.findByName('glyph') != null
    }
}
