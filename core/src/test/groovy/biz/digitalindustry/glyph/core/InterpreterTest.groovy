package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.Program
import org.junit.jupiter.api.Test

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals

class InterpreterTest {

    @Test
    void runsMainAndPrintsValues() {
        String source = '''
            fun void main() {
              val x: int = 2 + 3
              print("result:")
              print(x)
            }
        '''.stripIndent().trim()

        Path tmp = Files.createTempFile('glyph-script', '.gly')
        Files.writeString(tmp, source)
        Program program = GlyphParser.parse(tmp)

        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        PrintStream original = System.out
        System.setOut(new PrintStream(buffer))
        try {
            SimpleInterpreter.INSTANCE.eval(program)
        } finally {
            System.setOut(original)
            Files.deleteIfExists(tmp)
        }

        List<String> output = buffer.toString().trim().split(System.lineSeparator()) as List<String>
        assertEquals(['result:', '5'], output)
    }

    @Test
    void printsNullForSafeAccess() {
        String source = '''
            record User {
              val int age
            }
            fun void main() {
              val u: User? = null
              val a: int? = u?.age
              print(a)
              val u2: User = User { age = 9 }
              val a2: int = u2?.age
              print(a2)
            }
        '''.stripIndent().trim()

        Path tmp = Files.createTempFile('glyph-script', '.gly')
        Files.writeString(tmp, source)
        Program program = GlyphParser.parse(tmp)

        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        PrintStream original = System.out
        System.setOut(new PrintStream(buffer))
        try {
            SimpleInterpreter.INSTANCE.eval(program)
        } finally {
            System.setOut(original)
            Files.deleteIfExists(tmp)
        }

        List<String> output = buffer.toString().trim().split(System.lineSeparator()) as List<String>
        assertEquals(['null', '9'], output)
    }

    @Test
    void callsFunctionAndPrintsResult() {
        String source = '''
            fun int add(int a, int b) {
              return a + b
            }

            fun void main() {
              print(add(2, 3))
            }
        '''.stripIndent().trim()

        Path tmp = Files.createTempFile('glyph-script', '.gly')
        Files.writeString(tmp, source)
        Program program = GlyphParser.parse(tmp)

        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        PrintStream original = System.out
        System.setOut(new PrintStream(buffer))
        try {
            SimpleInterpreter.INSTANCE.eval(program)
        } finally {
            System.setOut(original)
            Files.deleteIfExists(tmp)
        }

        List<String> output = buffer.toString().trim().split(System.lineSeparator()) as List<String>
        assertEquals(['5'], output)
    }
}
