---
title = "Data Records"
layout = "default"
---

Excellent choice. `record` is:

* ✅ **Formal yet lightweight** — fits a systems language feel
* ✅ Familiar from **Java 14+**, but with your own spin
* ✅ Easy to parse and intuitive in type declarations
* ✅ Matches the mental model of immutable (or semi-immutable) data

---

## ✅ Glyph `record` Design Overview

### 🔸 Declaration

```glyph
record User {
  val string id
  string name
}
```

* `val` = immutable field
* No prefix = mutable field
* Enforces **explicit typing** per Glyph norms

### 🔸 Initialization

```glyph
val User u = User { id = "u1", name = "Alice" }
```

* Named initialization
* Field order **not** enforced
* All fields must be provided

### 🔸 Access and Mutation

```glyph
val id = u.id
u.name = "Bob"     // if field is mutable
```

Follows same dot-access model as arrays and maps.

---

## 🛠 Interoperability

| Concept              | Glyph Example                                       |
| -------------------- | --------------------------------------------------- |
| **Array of records** | `val users = [User] (10)`                           |
| **Map of records**   | `var db = [string: User] { "admin": User { ... } }` |
| **Function input**   | `func greet(User u): void { ... }`                  |

---

## 🧾 Grammar Additions

```ebnf
record_decl     ::= "record" IDENT "{" field_decl+ "}"
field_decl      ::= ( "val" )? type IDENT
record_literal  ::= IDENT "{" field_assignment ("," field_assignment)* "}"
field_assignment ::= IDENT "=" expression
```
