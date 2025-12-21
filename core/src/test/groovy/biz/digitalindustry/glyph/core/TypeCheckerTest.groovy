package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.Program
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class TypeCheckerTest {

    private Program parse(String source) {
        Path tmp = Files.createTempFile('glyph-test', '.gly')
        Files.writeString(tmp, source.trim())
        try {
            return GlyphParser.parse(tmp)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    @Test
    void detectsVarDeclTypeMismatch() {
        def program = parse('''
            fun void main() {
              val x: int = "foo"
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Type mismatch'))
        assertTrue(ex.message.contains('line') || ex.message.contains('['))
    }

    @Test
    void detectsBinaryTypeMismatch() {
        def program = parse('''
            fun void main() {
              val x: int = 1 + "a"
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Binary op'))
    }

    @Test
    void detectsReturnTypeMismatch() {
        def program = parse('''
            fun int main() {
              return "oops"
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Return type mismatch'))
    }

    @Test
    void allowsBoolLiteral() {
        def program = parse('''
            fun void main() {
              val ok: bool = true
              print(ok)
            }
        ''')
        new TypeChecker().check(program) // should not throw
    }

    @Test
    void rejectsNullAssignedToNonNullable() {
        def program = parse('''
            fun void main() {
              val name: string = null
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Type mismatch'))
    }

    @Test
    void infersTypeWhenOmittedAndDefaultsToVar() {
        def program = parse('''
            fun int main() {
              counter = 5
              counter + 1
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void allowsSafeFieldAccessOnNullable() {
        def program = parse('''
            record User {
              val int age
            }
            fun void main() {
              val u: User? = null
              val a: int? = u?.age
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void rejectsSafeFieldAccessAssignedToNonNullable() {
        def program = parse('''
            record User {
              val int age
            }
            fun void main() {
              val u: User? = null
              val a: int = u?.age
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Type mismatch'))
    }

    @Test
    void constMustBeLiteral() {
        def program = parse('''
            fun void main() {
              const total = 1 + 2
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('const'))
    }

    @Test
    void detectsFunctionCallArgumentMismatch() {
        def program = parse('''
            fun int helper(int value) {
              return value
            }

            fun void main() {
              helper("oops")
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Argument 1'))
    }

    @Test
    void allowsFunctionCallWithCorrectTypes() {
        def program = parse('''
            fun int add(int a, int b) {
              return a + b
            }

            fun void main() {
              val int value = add(2, 3)
              print(value)
            }
        ''')
        new TypeChecker().check(program)
    }
}
