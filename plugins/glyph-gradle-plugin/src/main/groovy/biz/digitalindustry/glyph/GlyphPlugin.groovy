package biz.digitalindustry.glyph

import org.gradle.api.Plugin
import org.gradle.api.Project

class GlyphPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.task('helloGlyph') {
            doLast {
                println 'Hello from the Glyph Gradle Plugin!'
            }
        }
    }
}
