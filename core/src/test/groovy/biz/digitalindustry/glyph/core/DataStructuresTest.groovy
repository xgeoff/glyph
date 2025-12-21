package biz.digitalindustry.glyph.core

import org.junit.jupiter.api.Test

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertThrows

class DataStructuresTest {

    private def parse(String source) {
        Path tmp = Files.createTempFile('glyph-data', '.gly')
        Files.writeString(tmp, source.trim())
        try {
            return GlyphParser.parse(tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    void recordLiteralAccessAndMutation() {
        def program = parse('''
            record User {
              val string id
              string name
            }

            fun void main() {
              val u: User = User { id = "u1", name = "Alice" }
              print(u.id)
              u.name = "Bob"
              print(u.name)
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
        assertEquals(['u1', 'Bob'], output)
    }

    @Test
    void arrayAndMapAccess() {
        def program = parse('''
            fun void main() {
              val nums: [int] = [int] (3)
              nums[0] = 7
              print(nums[0])

              val counts: [string:int] = [string:int] { "a": 1, "b": 2 }
              counts["a"] = 3
              print(counts["a"])
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
        assertEquals(['7', '3'], output)
    }

    @Test
    void recordValFieldIsImmutable() {
        def program = parse('''
            record User {
              val string id
              string name
            }

            fun void main() {
              val u: User = User { id = "u1", name = "Alice" }
              u.id = "u2"
            }
        ''')

        assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
    }
}
