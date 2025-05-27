
## ✅ `if` / `else`

### 🔹 Syntax

```glyph
if condition {
  // block
} else {
  // fallback block
}
```

### 🔸 Key Features

| Feature            | Behavior                                                  |
| ------------------ | --------------------------------------------------------- |
| Braces             | Always required (no single-line special case)             |
| `if` as expression | ✅ Can be assigned: `val x = if cond { ... } else { ... }` |
| Type consistency   | All branches must return same type (if assigned)          |
| Optional `else`    | ✅ Only required when used as an expression                |

---

## ✅ Ternary Expression

### 🔹 Syntax

```glyph
val result = condition ? value_if_true : value_if_false
```

### 🔸 Key Features

| Feature          | Behavior                                 |
| ---------------- | ---------------------------------------- |
| Expression-based | ✅ Always an expression                   |
| Optional usage   | ✅ Not required, alternative to `if`      |
| Clarity          | Best for short, inline conditional logic |

---

## ✅ Elvis Operator (Null Coalescing)

### 🔹 Syntax

```glyph
val y = x ?: fallback
```

### 🔸 Key Features

| Feature          | Behavior                                 |
| ---------------- | ---------------------------------------- |
| Null check       | Returns `x` if not null, else `fallback` |
| Expression-based | ✅                                        |
| Matches Kotlin   | ✅                                        |

---

## ✅ `match` Expression

### 🔹 Syntax

```glyph
val result = match value {
  1 = "one"
  2 = "two"
} else "other"
```

### 🔸 Key Features

| Feature            | Behavior                                                  |
| ------------------ | --------------------------------------------------------- |
| Expression-based   | ✅ Always returns a value                                  |
| `else` fallback    | ✅ Required unless all cases are exhaustive                |
| Syntax style       | `key = value` pairs, no fallthrough, no colons            |
| Outside `else`     | `else` clause placed after `}` block: `} else "fallback"` |
| Parsing simplicity | Clean AST separation of branches and default              |

---

## 🧾 Summary Table

| Construct | Glyph Syntax Example                       | Expression-Based | Required `else`     | Notes                               |
| --------- | ------------------------------------------ | ---------------- | ------------------- | ----------------------------------- |
| `if/else` | `if x > 10 { ... } else { ... }`           | ✅                | Only if assigned    | Preferred for branching logic       |
| Ternary   | `x > 10 ? "yes" : "no"`                    | ✅                | Always required     | Optional sugar for short logic      |
| Elvis     | `val y = x ?: 0`                           | ✅                | Always required     | Null-coalescing fallback            |
| `match`   | `match code { 200 = "OK" } else "Unknown"` | ✅                | ✅ unless exhaustive | No fallthrough; clean default style |

