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

---

## 🔧 Language Highlights

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

### Gradle Plugin Configuration

Apply the Glyph Gradle plugin and add a `glyph { … }` block in your `build.gradle`:

```groovy
plugins {
    id 'biz.digitalindustry.glyph'
}

glyph {
    entryFile = file('src/main/glyph/com/example/app/main.gly')
    // sourceDir defaults to src/main/glyph
    // grammarFile is optional; omit it to use the bundled glyph.peg
    // grammarFile = file('path/to/custom/glyph.peg')
}

> Debugging tip: add `-PglyphDebug=true` when running plugin tasks (e.g. `./gradlew -PglyphDebug=true glyphParse`) to log the indexed functions, records, and import paths resolved for the current project.
```

By default the plugin uses the grammar bundled inside `glyph-core`, so most projects do **not** need to set `grammarFile`. Tool authors can still point at a local PEG file when experimenting with new syntax.

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
