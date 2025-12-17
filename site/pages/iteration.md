---
title = "Iteration Utilities"
layout = "default"
---

# 📚 Glyph Standard Library: Iteration Utilities

---

## 🔹 `each` — Iterate Over Arrays

The `each` method lets you iterate over each element in an array using a clean block syntax. The loop variable `it` is implicitly available.

### ✅ Syntax

```glyph
array.each {
  // use `it` as the current element
}
```

### 🧾 Example

```glyph
val [string] names = [string] (3)
names[0] = "Alice"
names[1] = "Bob"
names[2] = "Cleo"

names.each {
  print("Hello, " + it)
}
```

---

## 🔹 `each` — Iterate Over Maps

For maps, `each` provides access to a synthetic `it` object containing `.key` and `.value`.

### ✅ Syntax

```glyph
map.each {
  // use it.key and it.value
}
```

### 🧾 Example

```glyph
val [string: int] scores = [string: int] {
  "Alice": 95,
  "Bob": 82
}

scores.each {
  print(it.key + " scored " + it.value)
}
```

---

## 🔹 `withIndex` — Iterate with Index (Arrays Only)

Use `withIndex` to access both the index and value of each array element. The loop variable `it` has two fields: `.index` and `.value`.

### ✅ Syntax

```glyph
array.withIndex {
  // it.index gives the position
  // it.value gives the element
}
```

### 🧾 Example

```glyph
val [string] names = [string] (3)
names[0] = "Alice"
names[1] = "Bob"
names[2] = "Cleo"

names.withIndex {
  print(it.index + ": " + it.value)
}
```

> Note: `withIndex` is only supported on arrays. Maps do not guarantee ordering.

---

## ✅ Summary Table

| Method      | Applies To | `it` Fields            | Notes                  |
| ----------- | ---------- | ---------------------- | ---------------------- |
| `each`      | Arrays     | `it` = element         | Iterate over values    |
| `each`      | Maps       | `it.key`, `it.value`   | Iterate over key-value |
| `withIndex` | Arrays     | `it.index`, `it.value` | Indexed iteration      |
