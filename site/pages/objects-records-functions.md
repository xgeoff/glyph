

# Glyph Specification: Data-Oriented Programming Model

## 1. Design Philosophy

Glyph follows a **data-oriented, function-driven** programming model.

> **Records hold data.
> Functions express behavior.
> Namespaces organize code.**

Glyph deliberately avoids traditional object-oriented constructs such as:

* classes with methods
* inheritance
* interfaces
* polymorphism
* implicit receivers (`this`)

This keeps the language:

* predictable
* WASM-friendly
* easy to compile
* easy to reason about

---

## 2. Data Objects (`record`)

### 2.1 Purpose

A `record` defines a **pure data structure** with named, typed fields.

Records:

* contain **no behavior**
* define **no methods**
* are used to model state and structured input/output

### 2.2 Syntax

```glyph
record User {
  val string id
  string name
  int age
}
```

### 2.3 Rules

* All fields must be explicitly typed
* Fields may be immutable (`val`) or mutable (default)
* Records cannot define functions
* Records do not imply encapsulation — access is explicit

### 2.4 Usage

```glyph
val User u = User {
  id = "u1"
  name = "Alice"
  age = 30
}

u.name = "Bob"
println(u.age)
```

---

## 3. Functions (Behavior)

### 3.1 Purpose

All behavior in Glyph is expressed using **free functions**.

Functions:

* are never instantiated
* do not belong to records
* operate on data passed explicitly as parameters

### 3.2 Syntax

```glyph
fun void greet(User u) {
  println("Hello " + u.name)
}
```

### 3.3 Behavioral Model

* Functions may read and mutate records passed to them
* Any form of encapsulation is **by convention**, not enforced
* The first parameter often represents the “primary data object”

This allows users to emulate OO-style patterns **without OO semantics**.

---

## 4. Encapsulation by Convention (Not Enforcement)

Glyph allows developers to structure code in an OO-like way *without* language-level support.

Example:

```glyph
record Counter {
  int value
}

fun void increment(Counter c) {
  c.value++
}

fun int read(Counter c) {
  c.value
}
```

This pattern provides:

* explicit state
* explicit mutation
* clear data flow
* no hidden behavior

Glyph does not restrict access to record fields — discipline is left to the developer.

---

## 5. Namespaces and Files

### 5.1 Every File Has a Namespace

Each `.gly` file must declare exactly one `package`:

```glyph
package com.example.users
```

The namespace:

* scopes records and functions
* prevents naming collisions
* determines import paths

---

## 6. Defining Records and Functions Across Files

### 6.1 Example Project Structure

```
src/
 └─ main/
    └─ glyph/
       └─ com/
          └─ example/
             ├─ users/
             │  └─ user.gly
             └─ services/
                └─ greeting.gly
```

---

### 6.2 Defining a Record (File A)

```glyph
// user.gly
package com.example.users

record User {
  val string id
  string name
}
```

---

### 6.3 Defining a Function That Uses the Record (File B)

```glyph
// greeting.gly
package com.example.services

import com.example.users.User

fun void greet(User u) {
  println("Hello " + u.name)
}
```

---

## 7. Imports

### 7.1 Importing Types and Functions

Glyph supports explicit imports:

```glyph
import com.example.users.User
import com.example.services.greet
```

Imported symbols:

* may be records or functions
* are referenced by simple name after import
* must be unambiguous

### 7.2 Fully Qualified Access (Optional)

Users may refer to symbols without importing:

```glyph
com.example.services.greet(user)
```

This is functionally identical but more verbose.

---

## 8. Invocation Across Namespaces

### 8.1 End-User Example

```glyph
package com.example.app

import com.example.users.User
import com.example.services.greet

fun void main() {
  val User u = User {
    id = "42"
    name = "Geoff"
  }

  greet(u)
}
```

---

## 9. Explicit Non-Goals (Important)

The following are **explicitly not part of Glyph**:

| Feature                 | Status       |
| ----------------------- | ------------ |
| Methods on records      | ❌            |
| `object.method()` sugar | ❌ (deferred) |
| Inheritance             | ❌            |
| Interfaces / traits     | ❌            |
| Virtual dispatch        | ❌            |
| Implicit receivers      | ❌            |

If OO-style method syntax is ever added, it will be **pure syntactic desugaring** and not required for correct usage.

---

## 10. Compiler Implications (For Codex)

### 10.1 Records

* Records compile to:

    * WASM linear memory layouts
    * JVM data classes / records
* No vtables
* No method tables
* No runtime metadata required beyond layout

### 10.2 Functions

* Functions compile to:

    * Top-level WASM functions
    * Static JVM methods
* Names are mangled using full namespace paths
* Function calls are always statically resolved

### 10.3 Imports

* Imports are compile-time only
* All symbol resolution is static
* Cross-file linking happens during the final link phase

---

## 11. Summary

> Glyph is a **data-oriented language** with:
>
> * structured records
> * explicit functions
> * strong namespacing
> * zero OO runtime semantics

Developers who want OO-style structure can build it **explicitly**, without forcing complexity on the language or the compiler.
