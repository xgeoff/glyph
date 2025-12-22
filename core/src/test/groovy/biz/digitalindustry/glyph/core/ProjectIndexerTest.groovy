package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.Program
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertTrue

class ProjectIndexerTest {

    @Test
    void indexesFunctionsAndRecordsWithFullyQualifiedNames() {
        Path dir = Files.createTempDirectory('glyph-index')
        try {
            Path file = dir.resolve('entry.gly')
            Files.writeString(file, '''
                package demo.core

                record User {
                  val string name
                }

                fun void main() {
                  print("demo")
                }
            '''.stripIndent().trim())

            ProjectIndex index = ProjectIndexer.index(dir)
            assertTrue(index.functionsByFqn.containsKey('demo.core.main'))
            assertTrue(index.recordsByFqn.containsKey('demo.core.User'))
        } finally {
            deleteDir(dir)
        }
    }

    @Test
    void symbolResolverMakesPackageAndImportsAvailable() {
        Path dir = Files.createTempDirectory('glyph-symbols')
        try {
            Path math = dir.resolve('math.gly')
            Files.writeString(math, '''
                package com.example.math

                fun int add(int a, int b) {
                  return a + b
                }
            '''.stripIndent().trim())

            Path helper = dir.resolve('helper.gly')
            Files.writeString(helper, '''
                package com.example.app

                fun void helper() {
                  print("hi")
                }
            '''.stripIndent().trim())

            Path main = dir.resolve('main.gly')
            Files.writeString(main, '''
                package com.example.app

                import com.example.math.add

                fun void main() {
                  print("ready")
                }
            '''.stripIndent().trim())

            ProjectIndex index = ProjectIndexer.index(dir)
            SymbolResolver resolver = new SymbolResolver(index)
            Program program = index.programsByFile[main.toAbsolutePath().normalize()]
            ResolvedSymbols symbols = resolver.resolve(program)

            assertNotNull(symbols.function('add'), 'imported function should be available')
            assertNotNull(symbols.function('helper'), 'package-level function should be available')
        } finally {
            deleteDir(dir)
        }
    }

    @Test
    void indexesLibraryDirectoriesWhenProvided() {
        Path projectDir = Files.createTempDirectory('glyph-project')
        Path libDir = Files.createTempDirectory('glyph-lib')
        try {
            Path libFile = libDir.resolve('std.gly')
            Files.writeString(libFile, '''
                package std.io

                fun void writeln(string value) { }
            '''.stripIndent().trim())

            ProjectIndex index = ProjectIndexer.index(projectDir, null, [libDir])
            assertTrue(index.functionsByFqn.containsKey('std.io.writeln'))
        } finally {
            deleteDir(projectDir)
            deleteDir(libDir)
        }
    }

    @Test
    void resolverExposesImportedTypeAliases() {
        Path dir = Files.createTempDirectory('glyph-aliases')
        try {
            Path types = dir.resolve('types.gly')
            Files.writeString(types, '''
                package com.example.types

                type UserId = string
            '''.stripIndent().trim())

            Path main = dir.resolve('main.gly')
            Files.writeString(main, '''
                package com.example.app

                import com.example.types.UserId

                fun void main() {
                  val UserId id = "abc"
                }
            '''.stripIndent().trim())

            ProjectIndex index = ProjectIndexer.index(dir)
            SymbolResolver resolver = new SymbolResolver(index)
            Program program = index.programsByFile[main.toAbsolutePath().normalize()]
            ResolvedSymbols symbols = resolver.resolve(program)
            assertNotNull(symbols.alias('UserId'), 'alias should be available via imports')
        } finally {
            deleteDir(dir)
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null) return
        if (!Files.exists(dir)) return
        def stream = Files.walk(dir)
        try {
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        } finally {
            stream.close()
        }
    }
}
