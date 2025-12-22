package biz.digitalindustry.glyph.core

import org.junit.jupiter.api.Test

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

class ControlFlowTest {

    private def parse(String source) {
        Path tmp = Files.createTempFile('glyph-control', '.gly')
        Files.writeString(tmp, source.trim())
        try {
            return GlyphParser.parse(tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    void ifExpressionRequiresMatchingBranchTypes() {
        def program = parse('''
            fun void main() {
              val x: int = if true {
                "bad"
              } else {
                1
              }
            }
        ''')
        assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
    }

    @Test
    void ternaryAndMatchWorkInInterpreter() {
        def program = parse('''
            fun void main() {
              val x: int = true ? 2 : 3
              print(x)
              val y: string = match 2 {
                1 -> "one"
                2 -> "two"
              } else "other"
              print(y)
              val z: int = if true {
                7
              } else {
                9
              }
              print(z)
            }
        ''')

        def buffer = new ByteArrayOutputStream()
        def original = System.out
        System.setOut(new PrintStream(buffer))
        try {
            SimpleInterpreter.INSTANCE.eval(program)
        } finally {
            System.setOut(original)
        }

        def output = buffer.toString().trim().split(System.lineSeparator()) as List<String>
        assertEquals(['2', 'two', '7'], output)
    }

    @Test
    void elvisRequiresMatchingTypes() {
        def program = parse('''
            fun void main() {
              val name: string = "a" ?: "b"
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void nullableElvisAllowsFallbackToNonNull() {
        def program = parse('''
            fun void main() {
              val name: string? = null
              val safe: string = name ?: "fallback"
              print(safe)
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void matchPatternBindingsAreScopedPerBranch() {
        def program = parse('''
            record User {
              string name
            }

            fun void main() {
              val string name = "outer"
              val User u = User { name = "inner" }
              val string result = match u {
                User { name = name } -> name
              } else "none"
              print(name)
              print(result)
            }
        ''')

        def buffer = new ByteArrayOutputStream()
        def original = System.out
        System.setOut(new PrintStream(buffer))
        try {
            SimpleInterpreter.INSTANCE.eval(program)
        } finally {
            System.setOut(original)
        }

        def output = buffer.toString().trim().split(System.lineSeparator()) as List<String>
        assertEquals(['outer', 'inner'], output)
    }
}
