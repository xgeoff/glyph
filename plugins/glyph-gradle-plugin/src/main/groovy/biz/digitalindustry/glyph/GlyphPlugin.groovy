package biz.digitalindustry.glyph

import biz.digitalindustry.glyph.core.ProjectIndex
import biz.digitalindustry.glyph.core.ProjectIndexer
import biz.digitalindustry.glyph.core.TypeChecker
import biz.digitalindustry.glyph.core.NoopCompiler
import biz.digitalindustry.glyph.core.WasmCompiler
import biz.digitalindustry.glyph.core.JvmCompiler
import biz.digitalindustry.glyph.core.ast.Program
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.logging.Logger

import java.nio.file.Path

class GlyphPlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        def extension = project.extensions.create('glyph', GlyphPluginExtension, project)

        project.tasks.register('helloGlyph') {
            group = 'glyph'
            description = 'Proof-of-life task for the Glyph Gradle plugin.'
            doLast {
                println 'Hello from the Glyph Gradle Plugin!'
            }
        }

        project.tasks.register('glyphParse') {
            group = 'glyph'
            description = 'Parse Glyph sources using glyph-core and the shared PEG grammar.'
            inputs.dir({ extension.sourceDir })
            inputs.file({ extension.entryFile })
            inputs.files({ extension.grammarFile ? [extension.grammarFile] : [] })

            doLast {
                parseAndCompile(project, extension as GlyphPluginExtension)
            }
        }

        project.tasks.register('glyphCompileWasm') {
            group = 'glyph'
            description = 'Compile Glyph sources to WAT/WASM using glyph-core.'
            inputs.dir({ extension.sourceDir })
            inputs.file({ extension.entryFile })
            inputs.files({ extension.grammarFile ? [extension.grammarFile] : [] })
            outputs.dir(project.layout.buildDirectory.dir("wasm"))

            doLast {
                compileWasm(project, extension as GlyphPluginExtension)
            }
        }

        project.tasks.register('glyphCompileJar') {
            group = 'glyph'
            description = 'Compile Glyph sources to a runnable JVM JAR using glyph-core.'
            inputs.dir({ extension.sourceDir })
            inputs.file({ extension.entryFile })
            inputs.files({ extension.grammarFile ? [extension.grammarFile] : [] })
            outputs.dir(project.layout.buildDirectory.dir("jvm"))

            doLast {
                compileJar(project, extension as GlyphPluginExtension)
            }
        }
    }

    private static void parseAndCompile(Project project, GlyphPluginExtension extension) {
        def context = loadProject(project, extension)
        if (!context) {
            return
        }
        project.logger.lifecycle("Parsed ${context.program.functions.size()} function(s) from ${extension.entryFile} within ${extension.sourceDir}")

        NoopCompiler.INSTANCE.compile(context.program)
    }

    private static void compileWasm(Project project, GlyphPluginExtension extension) {
        def context = loadProject(project, extension)
        if (!context) {
            return
        }

        def outputDir = project.layout.buildDirectory.dir("wasm").get().asFile
        outputDir.mkdirs()
        def emitWat = project.hasProperty('glyphEmitWat') && project.property('glyphEmitWat').toString().toBoolean()
        def compiler = new WasmCompiler()
        def wasmFile = new File(outputDir, "main.wasm")
        compiler.compile(context.index, context.program, wasmFile.toPath(), emitWat)
        project.logger.lifecycle("WASM written to ${wasmFile}${emitWat ? ' (WAT emitted alongside)' : ''}")
    }

    private static void compileJar(Project project, GlyphPluginExtension extension) {
        def context = loadProject(project, extension)
        if (!context) {
            return
        }

        def outputDir = project.layout.buildDirectory.dir("jvm").get().asFile
        outputDir.mkdirs()
        def jarFile = new File(outputDir, "main.jar")
        def compiler = new JvmCompiler()
        compiler.compileToJar(context.program, jarFile.toPath(), "GlyphMain", context.index)
        project.logger.lifecycle("JAR written to ${jarFile}")
    }

    private static CompilationContext loadProject(Project project, GlyphPluginExtension extension) {
        Logger logger = project.logger
        if (!extension.sourceDir?.exists()) {
            logger.lifecycle("No Glyph sources found under ${extension.sourceDir}. Skipping.")
            return null
        }
        if (!extension.entryFile?.exists()) {
            logger.lifecycle("Entry Glyph source not found at ${extension.entryFile}. Skipping.")
            return null
        }
        Path grammarPath = null
        if (extension.grammarFile) {
            if (!extension.grammarFile.exists()) {
                logger.warn("Glyph grammar not found at ${extension.grammarFile}. Falling back to bundled grammar.")
            } else {
                grammarPath = extension.grammarFile.toPath()
            }
        }

        List<Path> libraries = []
        if (extension.libDir?.exists()) {
            libraries << extension.libDir.toPath()
        } else if (extension.libDir) {
            logger.lifecycle("Configured Glyph stdlib not found at ${extension.libDir}. Continuing without it.")
        }
        ProjectIndex index = ProjectIndexer.index(extension.sourceDir.toPath(), grammarPath, libraries)
        Path entryPath = extension.entryFile.toPath().toAbsolutePath().normalize()
        Program program = index.programsByFile[entryPath]
        if (!program) {
            throw new IllegalArgumentException("Entry file ${entryPath} was not found within indexed sources in ${extension.sourceDir}")
        }
        if (!program.functions.any { it.name == 'main' }) {
            throw new IllegalArgumentException("Entry file ${entryPath} does not define a main() function")
        }

        if (project.hasProperty('glyphDebug')) {
            logger.lifecycle("Glyph debug: functions=${index.functionsByFqn.keySet()}")
            logger.lifecycle("Glyph debug: records=${index.recordsByFqn.keySet()}")
            program.imports.each { decl ->
                logger.lifecycle("Glyph debug: import '${decl.path}' (len=${decl.path?.length()})")
            }
        }

        new TypeChecker(index).check(program)
        return new CompilationContext(program, index)
    }

    private static class CompilationContext {
        final Program program
        final ProjectIndex index

        CompilationContext(Program program, ProjectIndex index) {
            this.program = program
            this.index = index
        }
    }
}

class GlyphPluginExtension {
    private final Project project
    File sourceDir
    File entryFile
    File grammarFile
    File libDir

    GlyphPluginExtension(Project project) {
        this.project = project
        this.sourceDir = project.file('src/main/glyph')
        this.entryFile = project.file('src/main/glyph/main.gly')
        this.grammarFile = null
        File defaultLib = project.rootProject.file('glyph-stdlib/src/main/glyph')
        this.libDir = defaultLib.exists() ? defaultLib : null
    }

    File getSourceFile() {
        return entryFile
    }

    void setSourceFile(Object file) {
        setEntryFile(file)
    }

    void setSourceDir(Object dir) {
        this.sourceDir = project.file(dir)
    }

    void setEntryFile(Object file) {
        this.entryFile = project.file(file)
    }

    void setGrammarFile(Object file) {
        this.grammarFile = project.file(file)
    }

    void setLibDir(Object dir) {
        this.libDir = dir ? project.file(dir) : null
    }
}
