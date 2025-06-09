
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



# 🔧 Core Type Reference: `bytes`

The `bytes` type in Glyph represents a raw binary buffer. It is used for working with encoded data, file I/O, network payloads, and other low-level operations where `string` is not appropriate.

---

## ✅ Syntax

### 🔹 Allocate a buffer

```glyph
val bytes buf = [bytes] (1024)  // 1024-byte buffer
```

### 🔹 Index and mutate

```glyph
buf[0] = 0x42
val int x = buf[1]
```

### 🔹 Length

```glyph
val int len = buf.length
```

---

## 🔁 Conversion: Encoding & Decoding

Glyph provides a fluent codec API for transforming between `string` and `bytes`.

### 🔹 Encode

```glyph
val bytes b = encode.utf8("hello")
```

* Converts a `string` to UTF-8 encoded bytes

### 🔹 Decode

```glyph
val string s = decode.utf8(b)
```

* Converts UTF-8 byte data to a string

### 🔹 Codec API Structure

| Namespace | Function       | Input    | Output   | Purpose                      |
| --------- | -------------- | -------- | -------- | ---------------------------- |
| `encode`  | `utf8(string)` | `string` | `bytes`  | Encode text as UTF-8 bytes   |
| `decode`  | `utf8(bytes)`  | `bytes`  | `string` | Decode UTF-8 bytes to string |

> The `utf8` codec is built-in. Additional codecs (e.g. `base64`, `hex`) may be added in future versions.

---

## 🧱 Buffer Operations

### 🔹 `append`

```glyph
val bytes c = a.append(b)
```

* Returns a new buffer containing the concatenation of `a` and `b`
* Does not mutate either input buffer

### 🔹 `slice`

```glyph
val bytes part = b.slice(0, 4)
```

* Returns a new buffer with a range of bytes from `b`
* Slices from `start` for `length` bytes
* Throws or truncates if out-of-bounds (TBD by runtime spec)

---

## ✅ Summary

| Operation        | Glyph Syntax             | Description                       |
| ---------------- | ------------------------ | --------------------------------- |
| Create buffer    | `[bytes] (size)`         | Allocates a byte array            |
| Access bytes     | `b[i]` / `b[i] = value`  | Reads or writes a byte at index   |
| Get length       | `b.length`               | Returns number of bytes           |
| Encode string    | `encode.utf8("text")`    | Returns UTF-8 encoded `bytes`     |
| Decode to string | `decode.utf8(bytes)`     | Returns `string` from UTF-8 bytes |
| Append buffers   | `a.append(b)`            | Concatenate two byte arrays       |
| Slice buffer     | `b.slice(start, length)` | Extract subrange of bytes         |

