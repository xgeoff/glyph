
## ✅ WASM Constraints: What We Can and Can’t Do

### 🔹 What WASM Supports

* **First-class functions** via function references (in `wasm64` with `reference types`)
* **Function pointers** (via `funcref`)
* **Indirect function calls** (`call_indirect`)
* **Closures?** ❌ Not directly — WASM **does not natively support closures with captured environments**

So for **MVP compatibility**, we must:

* Avoid captured closures
* Stick to **anonymous functions** that don’t capture external variables
* Represent lambdas as **function pointers or inline anonymous functions** with no lexical scope

---

## ✅ Glyph Lambda Design (WASM-Compatible MVP)

### 🔸 Syntax #1: Inline Anonymous Function

```glyph
val f = fun int (int x, int y) {
  x + y
}
```

### 🔸 Syntax #2: Assigned Named Function Pointer

```glyph
fun int add(int a, int b) {
  a + b
}

val f = add
```

---

## 🔧 Lambda Rules (MVP)

| Feature               | Support | Notes                                               |
| --------------------- | ------- | --------------------------------------------------- |
| Lambda declaration    | ✅       | Anonymous functions are first-class                 |
| Type required         | ✅       | No inference; always specify return and param types |
| No closure capture    | ❌       | WASM MVP doesn’t support it                         |
| Assign to variables   | ✅       | Function references                                 |
| Pass as arguments     | ✅       | If target supports `funcref`                        |
| Return from functions | ✅       | As long as it’s a function ref, not a closure       |
| Inline expressions    | Planned | Could add sugar like `=>` later, but not in MVP     |

---

## 🔸 Function Type Annotations

You’ll need a way to describe the function type explicitly:

```glyph
val (int, int) -> int f = fun int (int a, int b) {
  return a + b
}
```

But for MVP and parsing simplicity, we use **assignment-based type inference** for lambdas, like this:

```glyph
val f = fun int (int a, int b) { a + b }  // type of f is function from (int, int) to int
```

---

## 🧠 Compiler Representation

WASM translation would look like:

```wasm
(type $add_fn (func (param i32 i32) (result i32)))
(func $add (type $add_fn) ... )
(global $f (ref func) (ref.func $add))
(call_ref $f ...)
```

---

## ✅ Summary: Glyph Lambda Model

| Concept            | Glyph Syntax Example                                 |
| ------------------ | ---------------------------------------------------- |
| Lambda declaration | `val f = fun int (int x, int y) { x + y }`           |
| Function pointer   | `val f = add`                                        |
| Call lambda        | `val result = f(3, 4)`                               |
| No closure capture | `val n = 10; val f = fun int () { n + 1 }` ❌ Invalid |
| Return a lambda    | `return fun int (int x) { x * 2 }`                   |

