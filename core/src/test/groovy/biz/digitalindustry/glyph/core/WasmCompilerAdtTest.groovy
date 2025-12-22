package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.Program
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator

import static org.junit.jupiter.api.Assertions.assertTrue

class WasmCompilerAdtTest {

    @Test
    void compilesAdtProgramToWat() {
        Path dir = Files.createTempDirectory('glyph-wasm-adt')
        try {
            Path file = dir.resolve('main.gly')
            Files.writeString(file, '''
                package com.example.app

                type Result =
                  | Ok(value: int)
                  | Err(message: string)

                fun int eval(Result r) {
                  match r {
                    Ok(v) -> v
                    Err(_) -> 0
                  } else 0
                }

                fun void main() {
                  eval(Ok(42))
                  eval(Err("boom"))
                }
            '''.stripIndent().trim())

            ProjectIndex index = ProjectIndexer.index(dir)
            Program program = index.programsByFile[file.toAbsolutePath().normalize()]
            Path wat = dir.resolve('out.wat')
            new WasmCompiler().compileToWat(program, wat, index)
            assertTrue(Files.exists(wat))
            assertTrue(Files.size(wat) > 0)
        } finally {
            deleteDir(dir)
        }
    }

    private static void deleteDir(Path dir) {
        if (dir == null) {
            return
        }
        if (!Files.exists(dir)) {
            return
        }
        Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
    }
}
