package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.Program
import biz.digitalindustry.glyph.core.ast.LambdaExpr
import biz.digitalindustry.glyph.core.ast.VarDecl
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
    void allowsRelationalOperators() {
        def program = parse('''
            fun void main() {
              val bool a = 2 < 3
              val bool b = 3 >= 2
              val bool c = "x" == "x"
              val bool d = 1 != 2
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void rejectsInvalidRelationalOperands() {
        def program = parse('''
            fun void main() {
              val bool bad = "x" < "y"
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

    @Test
    void allowsTypeAliasUsage() {
        def program = parse('''
            type UserId = int

            record User {
              val UserId id
            }

            fun void main() {
              val UserId id = 5
              val User u = User { id = id }
              print(u.id)
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void typeAliasMustReferenceKnownType() {
        def program = parse('''
            type Mystery = DoesNotExist

            fun void main() {
              val Mystery x = null
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Unknown type'))
    }

    @Test
    void detectsCircularTypeAlias() {
        def program = parse('''
            type Foo = Bar
            type Bar = Foo

            fun void main() {
              val Foo x = 1
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Circular type alias'))
    }

    @Test
    void allowsLambdaCaptureOfImmutableValues() {
        def program = parse('''
            fun void main() {
              val int base = 5
              val inc = fun int (int value) {
                base + value
              }
              val int result = inc(10)
              print(result)
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void rejectsLambdaCapturingMutableVar() {
        def program = parse('''
            fun void main() {
              var int counter = 0
              val bad = fun int (int delta) {
                counter + delta
              }
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Lambda cannot capture mutable variable'))
    }

    @Test
    void infersLambdaReturnTypeFromBody() {
        def program = parse('''
            fun void main() {
              val doubler = fun (int value) {
                value + value
              }
              val int four = doubler(2)
              print(four)
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void propagatesNestedCapturesThroughOuterLambdas() {
        def program = parse('''
            fun void main() {
              val int globalOffset = 7
              val outer = fun int (int seed) {
                val middle = fun int (int value) {
                  val inner = fun int (int reading) {
                    reading + globalOffset
                  }
                  inner(value)
                }
                middle(seed)
              }
              print(outer(5))
            }
        ''')
        new TypeChecker().check(program)

        def mainStmt = program.functions[0].body.statements
        def outerLambda = (mainStmt[1] as VarDecl).value as LambdaExpr
        def middleLambda = (outerLambda.body.statements[0] as VarDecl).value as LambdaExpr
        def innerLambda = (middleLambda.body.statements[0] as VarDecl).value as LambdaExpr

        assertTrue(innerLambda.captures*.name.contains('globalOffset'))
        assertTrue(middleLambda.captures*.name.contains('globalOffset'))
        assertTrue(outerLambda.captures*.name.contains('globalOffset'))
    }

    @Test
    void matchRequiresElseOrWildcard() {
        def program = parse('''
            fun void main() {
              val int value = match 1 {
                1 -> 10
              }
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('match expression requires a wildcard case or else branch'))
    }

    @Test
    void sumMatchCanOmitElseWhenExhaustive() {
        def program = parse('''
            type Result =
              | Ok(value: int)
              | Err(message: string)

            fun void main() {
              val Result value = Ok(5)
              val int output = match value {
                Ok(v) -> v
                Err(_) -> 0
              }
              print(output)
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void sumMatchWithoutElseMustCoverAllVariants() {
        def program = parse('''
            type Result =
              | Ok(value: int)
              | Err(message: string)

            fun void main() {
              val Result value = Ok(5)
              val int output = match value {
                Ok(v) -> v
              }
              print(output)
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('match expression requires a wildcard case or else branch'))
    }

    @Test
    void matchBranchesMustShareType() {
        def program = parse('''
            fun void main() {
              val result = match 1 {
                1 -> 5
                2 -> "two"
              } else 7
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('match branches must return same type'))
    }

    @Test
    void recordPatternMustMatchRecordType() {
        def program = parse('''
            record User {
              string name
            }
            record Order {
              string name
            }
            fun void main() {
              val User u = User { name = "a" }
              val string label = match u {
                Order { name = n } -> n
              } else "none"
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('does not match'))
    }

    @Test
    void recordPatternAllowsBraceSyntax() {
        def program = parse('''
            record User {
              string name
            }
            fun void main() {
              val User u = User { name = "inner" }
              val string label = match u {
                User { name = n } -> n
              } else "none"
              print(label)
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void allowsSumTypeConstructionAndMatch() {
        def program = parse('''
            type Result =
              | Ok(value: int)
              | Err(message: string)

            fun string describe(Result r) {
              match r {
                Ok(value) -> "ok"
                Err(msg) -> msg
              } else "unknown"
            }

            fun void main() {
              val Result r = Ok(42)
              val string desc = describe(r)
              print(desc)
            }
        ''')
        new TypeChecker().check(program)
    }

    @Test
    void variantConstructorArgumentMismatchFails() {
        def program = parse('''
            type Result =
              | Ok(value: int)

            fun Result build() {
              Ok("oops")
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('Argument 1 for Ok'))
    }

    @Test
    void variantPatternMustMatchDeclaredSumType() {
        def program = parse('''
            type Alpha =
              | First()

            type Beta =
              | Second()

            fun void main() {
              val Alpha value = First()
              val string label = match value {
                Second() -> "bad"
              } else "ok"
              print(label)
            }
        ''')
        def ex = assertThrows(IllegalArgumentException) {
            new TypeChecker().check(program)
        }
        assertTrue(ex.message.contains('not found'))
    }
}
