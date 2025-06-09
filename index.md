---
layout: default
title: Welcome
---

# 🌟 Glyph Programming Language

**Glyph** is a modern, statically typed programming language designed for seamless compilation to both **WebAssembly (WASM)** and the **Java Virtual Machine (JVM)**. It blends the expressive syntax of Groovy and Kotlin with the performance-conscious mindset of systems languages—optimized for cloud-native, serverless, and edge-computing use cases.

---

## 🚀 What Glyph Aims to Do

Glyph is built to:

* ✨ **Enable clean, expressive code** with lightweight syntax and minimal ceremony.
* 🛠 **Target WASM-first** with efficient, closure-free output for Cloudflare Workers, Fastly, and beyond.
* 🔁 **Support optional JVM output** for compatibility with Java ecosystems.
* 📦 **Structure large projects cleanly** using packages and namespaces.
* 🧰 **Use Gradle tooling** for project builds, multi-target compilation, and deployment workflows.
* 🧭 **Support both functional and object-style programming** with first-class functions, records, and expressive control flow.
---

## 🔧 Language Highlights

* **Favor composition over inheritance** encouraging reusable functions and clean data modeling without complex hierarchies
* While Glyph supports **object-style data modeling and functional programming**, it deliberately avoids traditional object-oriented inheritance and polymorphism—favoring simplicity and WASM-compatibility.
* **Explicit variable declarations** (no inference):

  ```glyph
  val int x = 10
  string name = "Glyph"
  ```

* **Pure function syntax** with expression-based returns:

  ```glyph
  fun int add(int a, int b) {
    a + b
  }
  ```

* **First-class, WASM-compatible anonymous functions** (no closures):

  ```glyph
  val f = fun int (int x, int y) { x + y }
  ```

* **Record types for structured data**:

  ```glyph
  record User {
    val string id
    string name
  }
  ```

* **Rich control flow and null safety**:

  ```glyph
  val result = match code {
    200 = "OK"
    404 = "Not Found"
  } else "Unknown"

  val name: string? = null
  val display = name ?: "Anonymous"
  ```

* **Arrays and maps with Groovy-inspired syntax**:

  ```glyph
  val [int] nums = [int] (10)
  var [string: int] ages = [string: int] { "Bob": 42 }
  ```

* **Fluent iteration and range utilities**:

  ```glyph
  names.each { print(it) }
  range(0, 5).each { print(it) }
  ```

---

## 🧱 Output & Tooling

* 🧩 **Single `.wasm` binary** per project, perfect for serverless platforms.
* 🪄 **Optional JVM backend** via transpilation.
* ⚙️ **Gradle plugin** to drive compilation, build tasks, and linking.
* 🧪 IR & metadata per file to support linking, plugins, and advanced tooling.

---

## 📁 Example Project Layout

```
/my-glyph-project
├── build.gradle
├── src/
│   └── main/glyph/com/example/app/main.gly
├── build/
│   ├── wasm/    # → main.wasm
│   └── jvm/     # → .class / .jar
```

---

## 🧠 Why Glyph?

Most languages either focus on the JVM or offer clunky, indirect paths to WASM. Glyph is different. It was **built for WASM from the start**, with syntax and semantics designed to compile cleanly and perform predictably on low-overhead runtimes—**while retaining developer ergonomics.**

---

## 💡 Status

Glyph is in **active development**, with a focus on:

* Full MVP support for WASM and JVM targets
* Gradle integration
* Standard library primitives and type system finalization

Stay tuned as we move toward alpha release!

# Welcome to the Glyph programming language

## Documentation Index

- [Project Structure Approach](docs/project-structure-approach.md)
- [Variable Declaration Model](docs/variable-declaration-model.md)
- [Primitive Type Mapping](docs/primitive-type-mapping.md)
