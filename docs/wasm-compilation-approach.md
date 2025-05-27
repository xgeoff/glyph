
## ✅ WASM Compilation Approach in Glyph


### 🔹 **One Final `.wasm` per Project** ✅ *Recommended for Deployment*

* All `.gly` files in all packages are compiled together into **one unified `.wasm` module**.
* Compiler:

    * Resolves all symbols across files
    * Flattens to a single module with all exported functions/types/memory

✅ This is what **serverless platforms expect**.

---

## 🔧 What Serverless Platforms Expect (Cloudflare, Fastly, etc.)

| Platform                 | WASM Requirements                                                       |
| ------------------------ | ----------------------------------------------------------------------- |
| **Cloudflare Workers**   | ✅ Single `.wasm` module <br> ✅ Exports `main()` or named entrypoint     |
| **Fastly Compute\@Edge** | ✅ Single `.wasm` module <br> ✅ Exports `_start`, or Fastly-specific ABI |
| **Wasmer**               | ✅ Single `.wasm` with WCGI or WASI interface                            |
| **WASMEdge**             | ✅ Single `.wasm`, supports WASI + HTTP embedding                        |

> 🔁 **All expect a *single binary module*** — not multiple `.wasm` files.

---

## 🏗 Recommended Compilation Pipeline

Here’s how **Glyph** should handle WASM output:

### 1. **Per-File IR + Metadata Generation**

For each `.gly` file:

```plaintext
→ build/glyph-ir/com.example.math.ops.glyphmod.json
→ build/glyph-ir/com.example.math.ops.ir.json
```

### 2. **Link-Time Compilation Pass**

Run the linker stage to:

* Resolve all function and type references
* Deduplicate types and constants
* Merge into **one `.wasm`**

```plaintext
→ build/wasm/myproject.wasm
```

Optionally:

* Use `glyphc --link` or Gradle task
* Generate `.wat` for debugging

---

## ✍️ Example Project: Cloudflare Worker

You'd end up with:

```
/dist
├── worker.js                # Cloudflare bootstrap file
├── glyph-runtime.js         # Optional JS wrapper for memory management
├── glyph.wasm               # 🔥 Final compiled Glyph output
```

```js
// worker.js (Cloudflare-style)
import wasm from './glyph.wasm';

export default {
  async fetch(request) {
    const instance = await WebAssembly.instantiate(wasm);
    return new Response(instance.exports.handle());
  }
}
```

---

## 🔁 Summary

| Compilation Stage     | Output                       | Notes                                     |
| --------------------- | ---------------------------- | ----------------------------------------- |
| Per file              | `.glyphmod.json`, `.ir.json` | For analysis, not deployed                |
| Final project link    | `myproject.wasm`             | ✅ Single `.wasm` for serverless platforms |
| Cloudflare-compatible | One `.wasm` + entrypoint     | Usually wrapped in a JS or TS file        |

