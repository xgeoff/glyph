
## ✅ Revised Glyph Function Syntax

### 🔸 Function Definition Format

To align with variable declarations (`val int x = 5`), we'll move the return type **before** the function name:

```glyph
fun int add(int a, int b) {
  return a + b
}
```

### 🔸 Structure Breakdown

```
fun <return_type> <name>(<typed_params>) { ... }
```

| Element         | Required? | Notes                                                     |
| --------------- | --------- | --------------------------------------------------------- |
| `fun`           | ✅         | Function keyword                                          |
| `<return_type>` | ✅         | Must be explicit (like all types in Glyph)                |
| `<name>`        | ✅         | Function name                                             |
| `<params>`      | ✅         | Typed parameters (no inference, like variables)           |
| `{}` block      | ✅         | Contains function body; return is optional (Kotlin-style) |

---

## ✅ Return Behavior

Glyph will mimic **Kotlin-style returns**:

* **Explicit `return`** is allowed
* **Last expression** in the function block is used as the return value (if compatible with return type)
* If return type is `void`, either:

    * Use `return` without value
    * Omit return statement altogether

### Examples:

```glyph
fun int square(int x) {
  x * x    // implicit return
}

fun void greet(string name) {
  print("Hello, " + name)  // no return needed
}
```

If a function has multiple branches, you must **use `return`** explicitly in each branch.

---

## ✅ Null Safety: Kotlin vs Glyph

### 🔹 Kotlin's Null Safety Model

In Kotlin:

* **Non-null by default**: `String` cannot be null
* Use `?` for nullable types: `String?`
* Compiler enforces null checks

```kotlin
val name: String = "Alice"     // never null
val nickname: String? = null   // nullable
```

Accessing nullable types safely:

```kotlin
nickname?.length           // safe call
nickname?.let { println(it) } // executes block if not null
val len = nickname ?: 0    // Elvis operator (default fallback)
```

---

### 🔹 Glyph Null Safety Proposal

#### ✅ Non-null by default

All types are non-null unless declared otherwise using `?` suffix:

```glyph
val string name = "Alice"       // never null
val string? nickname = null     // nullable
```

#### ✅ Type Rules

* `string` ≠ `string?`
* Must perform explicit null checks or use safe-access operators

#### ✅ Safe Access Operators (planned)

* `?.` → safe property or method call
* `?:` → Elvis operator for fallback

```glyph
val int? x = null
val int y = x ?: 0         // y = 0 if x is null

val int? len = nickname?.length
```

---

### 🔸 Compiler Enforcement

* Disallows passing `string?` to a function expecting `string`
* Forces null-check or Elvis fallback

```glyph
fun void greet(string name) {
  print("Hello, " + name)
}

greet(nickname)    // ❌ compile error if nickname is string?
greet(nickname ?: "friend")  // ✅
```

---

## 🧾 Updated Grammar Snippet

```ebnf
function_decl ::= "fun" type IDENT "(" param_list? ")" block
param         ::= type IDENT
type          ::= base_type ["?"]    // supports nullability suffix
```

---

## ✅ Summary

| Feature          | Kotlin                | Glyph                |
| ---------------- | --------------------- | -------------------- |
| Nullability      | `Type?`               | `type?`              |
| Safe access      | `?.`                  | `?.` (planned)       |
| Default fallback | `?:`                  | `?:` (planned)       |
| Return behavior  | Last expr or `return` | Same                 |
| Function syntax  | `fun name(): Type`    | `fun Type name(...)` |

