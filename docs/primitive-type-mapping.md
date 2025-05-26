
## ✅ Glyph Primitive Type Mapping

| Glyph Type | WASM Type | Bit Width | Description                       |
| ---------- | --------- | --------- | --------------------------------- |
| `int`      | `i32`     | 32 bits   | Signed integer                    |
| `long`     | `i64`     | 64 bits   | Signed long integer               |
| `float`    | `f32`     | 32 bits   | Floating-point (single-precision) |
| `double`   | `f64`     | 64 bits   | Floating-point (double-precision) |
| `bool`     | `i32`     | 32 bits   | 0 = false, 1 = true               |
| `char`     | `i32`     | 32 bits   | Unicode scalar value (UCS-4)      |
| `string`   | -         | (custom)  | UTF-8 bytes + length header       |

---

## 🧬 Syntax Examples

### Declaring variables:

```glyph
val int age = 42
val float price = 19.99
var name = "Glyph"
```

### Arrays:

```glyph
val nums =  [int] (10)
var values = [double] (5)
const bits = [bool] (64)
```

### Maps:

```glyph
var table = [string: int] (32)
val hex = [string: int] { "a": 10, "b": 11 }
```

---

## 🧱 Optional: Under-the-Hood Mapping for Compiler

Your compiler will map these internally:

```glyph
// Input (Glyph):
val int x = 1

// Internal representation:
x: Type.Primitive(INT)   // maps to WASM i32

// In WASM:
(i32.const 1)
```

---

## 🔐 Reserved Type Keywords (MVP)

We now define this **official Glyph primitive type list**:

```
int, long, float, double, bool, char, string
```

> All lowercase, consistent, and friendly for WASM targets.

---

## 🧾 Summary

| Glyph Type | WASM Type | Notes                    |
| ---------- | --------- | ------------------------ |
| `int`      | `i32`     | default integer          |
| `long`     | `i64`     | for larger numbers       |
| `float`    | `f32`     | single-precision decimal |
| `double`   | `f64`     | high-precision decimal   |
| `bool`     | `i32`     | 0/1 values only          |
| `char`     | `i32`     | Unicode codepoint        |
| `string`   | —         | custom UTF-8 structure   |

