
## ✅ Glyph Variable Declaration Model

### 1. **Declaration Keywords**

| Keyword | Mutability            | Notes                                         |
| ------- | --------------------- | --------------------------------------------- |
| `val`   | Immutable             | Assigned once, like a final variable          |
| `var`   | Mutable               | Reassignable                                  |
| `const` | Compile-time constant | Inlined at compile time, no memory allocation |

### 2. **Default Behavior**

If no keyword is specified → treat it as `var`

```glyph
x = 5  // Equivalent to: var x = 5
```

---

## 🧾 Examples

### Explicit Declaration

```glyph
val greeting: string = "hello"
var counter: int = 0
const pi: double = 3.14159
```

### Inferred Type

```glyph
val name = "Glyph"
var score = 42
const threshold = 1000
```

### Implicit `var`

```glyph
result = true      // parsed as: var result = true
```

---

## 🧠 Internal Representation (IR / AST)

Each variable declaration gets a flag:

```kotlin
enum class Mutability {
  CONST, VAL, VAR
}

data class VariableDecl(
  name: String,
  type: Type?,          // optional, for inference
  value: Expr,
  mutability: Mutability
)
```

---

## 🛠 Compiler Behavior by Type

### `var`:

* Allocated in memory (stack or linear heap)
* Value can be updated with `set_local` or memory `store`
* Default

### `val`:

* Allocated in memory
* Reassignment triggers compile error
* Accessed like `var`

### `const`:

* **No allocation**
* Value is inlined at all usage points (like `#define` or `constexpr`)
* Expression must be constant-evaluable at compile time

    * literals
    * static math
    * string literals

---

## ✳️ Syntax Grammar

```ebnf
declaration ::= ( "const" | "val" | "var" )? IDENTIFIER [ ":" type ] "=" expression
```

---

## 🧪 Future Considerations

| Feature                 | Support? | Notes                                   |
| ----------------------- | -------- | --------------------------------------- |
| Shadowing               | Optional | Allow inner scope to reuse name         |
| Block immutability      | No       | Per-variable only                       |
| Top-level const folding | ✅        | For things like `const VERSION = "1.0"` |

---

## ✅ Summary

| Keyword  | Mutability | In Memory? | Inlined? | Example              |
| -------- | ---------- | ---------- | -------- | -------------------- |
| `const`  | Immutable  | ❌          | ✅        | `const PI = 3.14`    |
| `val`    | Immutable  | ✅          | ❌        | `val name = "Glyph"` |
| `var`    | Mutable    | ✅          | ❌        | `var counter = 0`    |
| *(none)* | Mutable    | ✅          | ❌        | `flag = true`        |

---

