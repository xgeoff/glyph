---
title = "Gradle Plugin Reference"
layout = "default"
---

## 🔹 Applying the Plugin

Add the plugin to your build and point it at your Glyph sources:

```kotlin
plugins {
    id("biz.digitalindustry.glyph")
}

glyph {
    // defaults shown
    sourceDir = file("src/main/glyph")
    entryFile = file("src/main/glyph/main.gly")
    // optional: use a custom grammar
    grammarFile = file("../grammar/glyph.peg")
    // optional: point to stdlib sources (defaults to root/glyph-stdlib/src/main/glyph)
    libDir = file("../glyph-stdlib/src/main/glyph")
}
```

These defaults match the layout used in `examples/wasm-basic` and `examples/advanced-features`.

---

## 🔹 Tasks

| Task               | Description                                                                    |
| ------------------ | ------------------------------------------------------------------------------ |
| `helloGlyph`       | Proof‑of‑life task (`./gradlew helloGlyph`)                                    |
| `glyphParse`       | Parses + type‑checks all sources. Fails fast if grammar/type errors occur.     |
| `glyphCompileWasm` | Compiles the project to `build/wasm/main.wasm` (and optionally `main.wat`).    |
| `glyphCompileJar`  | Emits a runnable JVM JAR at `build/jvm/main.jar` (using the Groovy compiler).  |

All tasks live in the `glyph` group inside `./gradlew tasks`.

### Example

```bash
./gradlew glyphParse
./gradlew glyphCompileWasm -PglyphEmitWat=true
./gradlew glyphCompileJar
```

`glyphCompileWasm` honours the `glyphEmitWat` project property—set it to `true` to emit `main.wat` alongside `main.wasm`. When `glyphDebug` is present (any value), the plugin logs extra details about indexed functions, records, and imports.

---

## 🔹 Inputs & Outputs

* **Inputs**: everything under `glyph.sourceDir`, plus the configured `entryFile` and optional `grammarFile`.
* **Outputs**:
  * `glyphCompileWasm` → `build/wasm/main.wasm` (+ `main.wat` if enabled)
  * `glyphCompileJar` → `build/jvm/main.jar`

Gradle’s up‑to‑date checks work because the tasks declare these inputs/outputs—only changed files trigger recompilation.

---

## 🔹 Troubleshooting

| Symptom                                | Fix                                                                                 |
| -------------------------------------- | ----------------------------------------------------------------------------------- |
| “Entry file … does not define main()” | Ensure your `entryFile` contains `fun void main()`                                  |
| “Glyph grammar not found …”           | Double‑check `glyph { grammarFile = … }` or remove it to use the bundled grammar   |
| Want verbose indexing logs            | Run with `-PglyphDebug=true`                                                        |

Use the new `examples/wasm-abi-tests` or `examples/advanced-features` projects as templates—they already apply the plugin and wire the tasks into CI-friendly commands.
