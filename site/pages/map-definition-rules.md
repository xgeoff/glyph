---
title = "Map Definition Rules"
layout = "default"
---

## ✅ Revised Map Syntax Proposal (Groovy-Influenced)

### 🔹 1. **Literal map with typed keys/values**

```glyph
var mymap = [int: string] { 1: "one", 2: "two" }
```

* Type declaration is embedded in square brackets (`[K: V]`)
* Literal content is enclosed in `{ ... }`
* Clean, Groovy-esque, easy to parse

### 🔹 2. **Empty map with capacity**

```glyph
var mymap = [int: int] (12)
```

* Declares a map of `int → int` with capacity for 12 entries
* Short, expressive, and type-safe

### 🔹 3. **Quick string\:string default map**

```glyph
var mymap = [:] (10)
```

* Defaults to `[string: string]`
* Good for quick prototyping or typical web-ish usage
* `(10)` still conveys capacity

> ✔️ All of these can use `var` or `val` to control the mutability of the **map reference**, as per our declaration rules.

---

## 🧠 Compiler Interpretation

| Syntax           | Parsed As                                     |
| ---------------- | --------------------------------------------- |
| `[K: V] { ... }` | Typed map with literal entries                |
| `[K: V] (N)`     | Typed map with fixed capacity                 |
| `[:] (N)`        | Default to `string: string` map with capacity |

> All become `MapInitExpr` or `MapAllocExpr` in the AST, with embedded type info and optional values.

---

## ✍️ Syntax Grammar (EBNF-style)

```ebnf
map_type     ::= "[" type ":" type "]"
map_literal  ::= map_type "{" map_entry_list "}"
map_alloc    ::= map_type "(" expression ")"
map_shorthand::= "[:]" "(" expression ")"

map_entry_list ::= map_entry ("," map_entry)*
map_entry      ::= expression ":" expression
```

---

## 🔧 Type Inference & Mutability

```glyph
val m1 = [int: int] (64)         // immutable map reference
var m2 = [string: int] { "x": 1 } // mutable map reference
```

* **Map entries themselves are always mutable** (unless we support immutable maps later)
* **Variable binding (var/val/const)** determines whether the map reference can be reassigned

---

## ✅ Summary of Proposed Map Syntax

| Use Case            | Glyph Syntax Example                           |
| ------------------- | ---------------------------------------------- |
| Typed literal       | `val m = [int: string] { 1: "one" }`           |
| Empty with capacity | `var m = [string: float] (20)`                 |
| Shorthand default   | `val m = [:] (10)`  → assumes `string: string` |
| Immutable ref       | `val m = [int: int] (12)`                      |
| Mutable ref         | `var m = [int: int] (12)`                      |

