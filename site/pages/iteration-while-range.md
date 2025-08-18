
## ✅ Glyph `while` Loop — Final Design

### 🔹 Syntax

```glyph
while x < 10 {
  println x
  x++
}
```

### 🔸 Key Features

| Feature           | Behavior                              |
| ----------------- | ------------------------------------- |
| ✅ No parentheses  | Consistent with `if` and `match`      |
| ✅ Braces required | All blocks use `{}`                   |
| ✅ Condition first | Pre-condition check before block runs |
| ✅ Mutable vars    | Works with `var`-declared counters    |

---

## 🔧 Grammar (EBNF-style)

```ebnf
while_loop ::= "while" expression block
```

Where:

* `expression` → boolean expression (e.g. `x < 10`)
* `block` → `{ ... }`

---

## 🧾 Example

```glyph
var int x = 0

while x < 5 {
  println x
  x++
}
```

✅ Output:

```
0
1
2
3
4
```

---

## ✅ Summary

| Construct   | Syntax Example                           | Notes                    |
| ----------- | ---------------------------------------- | ------------------------ |
| `while`     | `while condition { ... }`                | Pre-condition loop       |
| `loop`      | `loop { ... }` + `break`                 | Infinite loop            |
| `each`      | `array.each { ... }`                     | Iterate over collections |
| `withIndex` | `array.withIndex { it.index, it.value }` | Indexed array iteration  |



# 📚 Glyph Standard Library: `range` Function

---

## ✅ Purpose

Produces an iterable numeric sequence that can be used with `.each` or `.withIndex`. Supports ascending and descending sequences, with an optional step.

---

## ✅ Syntax

```glyph
range(start, end)
range(start, end, step)
```

### 🔸 All values must be `int`.

---

## ✅ Type Signature

```glyph
fun [int] range(int start, int end)
fun [int] range(int start, int end, int step)
```

> Returns a **temporary iterable object**, internally backed by an array or generator-like structure depending on compilation target.

---

## 🧾 Examples

### 🔹 Count from 0 to 9 (exclusive)

```glyph
range(0, 10).each {
  print(it)
}
```

### 🔹 Count from 5 to 1 (descending)

```glyph
range(5, 0, -1).each {
  print(it)
}
```

### 🔹 Count by twos

```glyph
range(0, 10, 2).each {
  print(it)
}
```

---

## 🔧 Compiler Behavior

* Internally uses a `for`-loop-like expansion:

  ```glyph
  fun [int] range(int start, int end, int step) {
    val int len = abs((end - start) / step)
    val [int] result = [int] (len)
    var int i = 0
    var int current = start
    while (step > 0 and current < end) or (step < 0 and current > end) {
      result[i] = current
      i++
      current = current + step
    }
    result
  }
  ```

> Implementation may be optimized or even compiler-builtin, but this sketch defines the **semantic contract**.

---

## ⚠️ Notes

| Feature               | Behavior                     |
| --------------------- | ---------------------------- |
| Inclusive?            | ❌ No, `end` is **exclusive** |
| Negative step allowed | ✅ Yes                        |
| Step required?        | ❌ Optional (defaults to +1)  |
| Empty range behavior  | Returns empty array          |
| Bounds type           | `int` only (MVP)             |

---

## ✅ Summary

| Use Case         | Syntax Example    | Result            |
| ---------------- | ----------------- | ----------------- |
| Ascending range  | `range(0, 5)`     | `[0, 1, 2, 3, 4]` |
| Descending range | `range(5, 0, -1)` | `[5, 4, 3, 2, 1]` |
| Custom step      | `range(0, 10, 2)` | `[0, 2, 4, 6, 8]` |

