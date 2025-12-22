---
title = "Pattern Matching"
layout = "default"
---

# Pattern Matching

Glyph provides a lightweight `match` expression for branching on primitive
values and record structures without introducing OO-style semantics or
algebraic data types. Patterns are structural, branch bodies are expressions,
and every `match` must resolve to a single result type.

```glyph
val label = match status {
  200 -> "ok"
  404 -> "not-found"
} else "other"
```

## Core rules

- The subject expression is evaluated once; patterns are checked in order.
- The first matching pattern wins. An `else` branch is required unless a
  wildcard (`_`) or binding pattern covers all cases.
- Each branch expression must evaluate to the same type. A mismatch raises a
  compile-time error.

## Primitive equality

Use literal patterns for numeric, boolean, or string comparison. Pattern
literals must match the subject type.

```glyph
val message = match code {
  1 -> "one"
  2 -> "two"
} else "many"
```

## Record patterns

Records are matched structurally by field name. Mentioned fields must exist on
the record type; omitted fields are ignored. Fields may match literals, nested
patterns, or bindings.

```glyph
record User {
  string name
  string role
}

val summary = match user {
  User { role = "admin" } -> "full control"
  User { name = n }       -> "hi " + n
} else "guest"
```

Bindings introduced inside a pattern are scoped to that branch only and are
immutable.

## Wildcard

`_` matches anything and introduces no bindings. Use it as a catch-all branch:

```glyph
val label = match value {
  _ -> "anything"
} else "impossible"
```

## When to use match

- Converting status codes or enums to strings
- Selecting behavior based on record shape
- Destructuring a record while keeping Glyph data-oriented

## Limitations (MVP)

- Guards (`if` conditions in patterns) are not yet supported.
- Nested record patterns only match directly referenced fields.
- Exhaustiveness is enforced only via wildcard/else checks—no ADT awareness yet.
- `match` is expression-only; it does not introduce new statement forms.

Pattern matching stays true to Glyph’s design: records carry data, functions
carry behavior, and control flow remains explicit and predictable across the
WASM and JVM backends.
