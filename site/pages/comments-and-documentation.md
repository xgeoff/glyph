Perfect — here’s a **ready-to-drop-in Markdown spec** you can add directly to your Glyph docs.
It’s written in the same tone and structure as your existing specs, and intentionally scoped to MVP while leaving clean extension points.

You can save this as something like:

```
docs/comments-and-documentation.md
```

---

# 📝 Comments and Documentation in Glyph

## Overview

Glyph supports a minimal, explicit comment and documentation system designed to:

* Have **zero runtime impact**
* Be **trivial to parse and ignore**
* Support **tooling, IDEs, and doc generation**
* Remain **WASM-first and compilation-friendly**

Comments are treated as **whitespace** by the compiler and do not affect semantics or output, except for documentation comments, which may be preserved as **metadata** for tooling.

---

## 1. Line Comments

### Syntax

```glyph
// This is a line comment
```

### Behavior

* Begins with `//`
* Continues to the end of the line
* May appear anywhere whitespace is allowed
* Ignored entirely by the compiler
* Not preserved in IR or output

### Example

```glyph
var int count = 0   // initialize counter
count++             // increment
```

---

## 2. Block Comments

### Syntax

```glyph
/*
  This is a block comment
  spanning multiple lines.
*/
```

### Behavior

* Begins with `/*` and ends with `*/`
* May span multiple lines
* **Not nestable**
* Ignored entirely by the compiler
* Not preserved in IR or output

### Example

```glyph
/*
  This implementation is O(n²).
  Replace with a hashmap-based approach later.
*/
fun int slowSum([int] values) {
  ...
}
```

---

## 3. Documentation Comments

### Syntax

```glyph
/// Documentation comment
```

### Purpose

Documentation comments attach structured documentation to the **next declaration**.
They are intended for:

* API documentation
* IDE hover text
* Static doc generation
* Tooling and LSP support

---

## 4. Documentation Attachment Rules

### Key Rules

* Documentation comments **must immediately precede** a declaration
* One or more consecutive `///` lines are joined together
* Blank lines **break association**
* Attached to the next syntactic declaration only
* Ignored at runtime
* Optionally preserved in IR metadata

---

### Supported Targets

Documentation comments may be attached to:

* `package` declarations
* `fun` declarations
* `record` declarations
* `record` fields
* Top-level `val`, `var`, or `const` declarations

---

## 5. Examples

### Function Documentation

```glyph
/// Adds two integers.
/// Used by math and statistics modules.
fun int add(int a, int b) {
  a + b
}
```

---

### Record Documentation

```glyph
/// Represents a user account
record User {

  /// Unique identifier
  val string id

  /// Display name
  string name
}
```

---

### Package / File Documentation

```glyph
/// Math utilities for aggregation and statistics
package com.example.math
```

---

### Top-Level Value Documentation

```glyph
/// Default timeout in milliseconds
const int DEFAULT_TIMEOUT = 5000
```

---

## 6. Compiler Behavior

### Lexer

* `//` and `/* */` comments are treated as whitespace
* `///` produces a `DocComment` token

---

### Parser

* Consecutive `DocComment` tokens are accumulated
* Attached to the next declaration node
* Cleared after attachment
* No effect on parsing or control flow

---

### IR Representation (Optional)

Documentation comments may be preserved as metadata:

```json
{
  "kind": "Function",
  "name": "add",
  "docs": "Adds two integers.\nUsed by math and statistics modules."
}
```

This metadata:

* Is **not required** for code generation
* Is **not emitted** into WASM or JVM output
* May be consumed by tooling

---

## 7. Output and Runtime Impact

| Aspect           | Behavior                    |
| ---------------- | --------------------------- |
| WASM output      | No comments or docs emitted |
| JVM output       | No comments or docs emitted |
| Runtime behavior | Unaffected                  |
| Performance      | Unaffected                  |

Documentation exists **only at compile time** unless explicitly extracted by tools.

---

## 8. Explicit Non-Goals (MVP)

The following are intentionally **out of scope** for the initial Glyph documentation system:

* Markdown parsing inside documentation
* Javadoc-style tags (`@param`, `@return`)
* Inline documentation expressions
* Runtime reflection on documentation
* Annotation or decorator systems

These may be added later **without breaking syntax**.

---

## 9. Summary

| Feature                        | Supported |
| ------------------------------ | --------- |
| Line comments (`//`)           | ✅         |
| Block comments (`/* */`)       | ✅         |
| Documentation comments (`///`) | ✅         |
| Symbol-level docs              | ✅         |
| Runtime impact                 | ❌         |
| WASM-safe                      | ✅         |
| Tooling-friendly               | ✅         |

---

This design keeps Glyph documentation:

* **Predictable**
* **Explicit**
* **Toolable**
* **Zero-cost at runtime**

— fully aligned with Glyph’s WASM-first philosophy.

---

If you want next, I can:

* add a **short grammar appendix** (EBNF-style)
* design a `glyph doc` CLI command
* or write Codex instructions for implementing this in the lexer/parser cleanly
