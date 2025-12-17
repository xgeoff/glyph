---
title = "Variable Initialization Syntax"
layout = "default"
---

## ✅ Your Proposed Syntax: No Colons, No Inference

```glyph
val int myvariable = 1
var string mystringvariable = "hello"
string myotherstringvariable = "world"
```

### Structure:

```
[mutability] <type> <name> = <value>
```

| Element     | Required? | Default           | Notes                                |
| ----------- | --------- | ----------------- | ------------------------------------ |
| `val`/`var` | Optional  | defaults to `var` | Controls mutability                  |
| `<type>`    | ✅         | must be explicit  | No type inference — always specified |
| `<name>`    | ✅         | —                 | Variable identifier                  |
| `=`         | ✅         | —                 | Always requires initialization       |

---

## 🧠 Advantages of This Model

* ✅ **Cleaner to read**: No `:` or ambiguity between type and name
* ✅ **Easier to parse visually and syntactically**
* ✅ **Consistent structure**: Always `mutability type name = value`
* ✅ **No inference = no ambiguity**: Explicit typing is easier to compile and reason about
* ✅ **Optional mutability keyword** keeps things tidy

---

## ✍️ Updated Grammar (EBNF-style)

```ebnf
declaration ::= ( "val" | "var" )? type IDENTIFIER "=" expression
```

Examples:

```glyph
val int count = 42
float temperature = 98.6
const string greeting = "hello"
```

---

## ❌ What This Model Intentionally *Excludes*

| Feature                 | Why it's excluded               |
| ----------------------- | ------------------------------- |
| Type inference          | Avoids ambiguity and complexity |
| Type-colon (`:`) syntax | Too noisy, less visually clean  |
| Uninitialized variables | Forces explicit initialization  |

---

## 🔄 Comparison to Other Languages

| Language | Style            | Example                   |
| -------- | ---------------- | ------------------------- |
| Kotlin   | `val name: Type` | `val name: String = "hi"` |
| Go       | `var name Type`  | `var x int = 5`           |
| Glyph    | `val Type name`  | `val int age = 30`        |

You're choosing something closer to **Go**, but even more minimal — and with **optional `val/var`**. That’s a great sweet spot.

---

## ✅ Summary: Final Glyph Variable Declaration Model

| Syntax                      | Meaning                 |
| --------------------------- | ----------------------- |
| `val int x = 1`             | Immutable int           |
| `var string name = "glyph"` | Mutable string          |
| `float temp = 72.5`         | Defaults to `var`       |
| `const double pi = 3.14159` | Inlined at compile time |

