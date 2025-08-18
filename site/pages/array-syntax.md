
## ✅ ✅ Glyph Array Syntax (Official)

### 🔹 1. **Typed Allocation**

```glyph
val a = [int] (12)
```

* Declares an immutable array of 12 `int`s
* Allocates memory in WASM for `12 * sizeof(int)` bytes
* Type must always be specified

---

### 🔹 2. **Mutable Version**

```glyph
var a = [string] (5)
```

* Allows reassignment of the array variable (not elements — that depends on internal mutability)

---

### 🔹 3. **Planned (not MVP)**

#### A. Literal

```glyph
val a = [1, 2, 3]                // inferred as [int] (3)
val a = [int] { 1, 2, 3 }        // explicit form
```

#### B. Programmatic Fill

```glyph
val a = [int] (12) filled with 0
val a = [int] (12) using { i => i * 2 }
```

---

### 🔹 4. **Access and Mutation**

```glyph
val x = a[4]         // access
a[2] = 99            // set value
```

---

## ✍️ Syntax Grammar

```ebnf
array_decl   ::= "[" type "]" "(" expression ")"
array_access ::= IDENT "[" expression "]"
array_assign ::= IDENT "[" expression "]" "=" expression
```

---

## 📊 Consistency with Maps

| Feature     | Map             | Array                   |
| ----------- | --------------- | ----------------------- |
| Declaration | `[K:V] (cap)`   | `[T] (len)`             |
| Literal     | `[K:V] { ... }` | `[T] { ... }` (planned) |
| Default     | `[:] (10)`      | *(none; type required)* |
| Access      | `map["key"]`    | `arr[3]`                |
| Mutation    | `map[k] = v`    | `arr[i] = v`            |

---

Glyph arrays are now:

* ✅ Clean and expressive
* ✅ Visually and structurally consistent with maps
* ✅ Friendly for WASM codegen

