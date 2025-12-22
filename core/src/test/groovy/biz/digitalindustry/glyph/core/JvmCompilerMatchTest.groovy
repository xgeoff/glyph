package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.Program
import org.junit.jupiter.api.Test

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals

class JvmCompilerMatchTest {

    private Program parse(String source) {
        Path tmp = Files.createTempFile('glyph-jvm-match', '.gly')
        Files.writeString(tmp, source.trim())
        try {
            return GlyphParser.parse(tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private List<String> runInterpreter(Program program) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream()
        PrintStream original = System.out
        System.setOut(new PrintStream(buffer))
        try {
            SimpleInterpreter.INSTANCE.eval(program)
        } finally {
            System.setOut(original)
        }
        return buffer.toString().trim().split(System.lineSeparator()) as List<String>
    }

    private List<String> runJar(Program program) {
        Path jar = Files.createTempFile('glyph-match', '.jar')
        try {
            new JvmCompiler().compileToJar(program, jar)
            Path javaExec = Path.of(System.getProperty('java.home'), 'bin', 'java')
            Process process = new ProcessBuilder(javaExec.toString(), '-jar', jar.toString())
                    .redirectErrorStream(true)
                    .start()
            String output = process.inputStream.getText('UTF-8').trim()
            int exit = process.waitFor()
            if (exit != 0) {
                throw new IllegalStateException("java -jar exited with ${exit}: ${output}")
            }
            return output.isEmpty() ? [] : output.split(System.lineSeparator()) as List<String>
        } finally {
            Files.deleteIfExists(jar)
        }
    }

    @Test
    void matchPatternsBehaveSameInInterpreterAndJvm() {
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

        List<String> interpreter = runInterpreter(program)
        assertEquals(['outer', 'inner'], interpreter)

        List<String> jvm = runJar(program)
        assertEquals(interpreter, jvm)
    }

    @Test
    void variantPatternsBehaveSameInInterpreterAndJvm() {
        def program = parse('''
            type Result =
              | Ok(value: int)
              | Err(message: string)

            fun void describe(Result input) {
              val int amount = match input {
                Ok(v) -> v
                Err(msg) -> 0
              } else 0
              print(amount)
            }

            fun void main() {
              describe(Ok(42))
              describe(Err("boom"))
            }
        ''')

        List<String> interpreter = runInterpreter(program)
        List<String> jvm = runJar(program)
        assertEquals(interpreter, jvm)
    }
}
