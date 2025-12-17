---
title = "Project Structure Approach"
layout = "default"
---

## ✅ Overview of Goals

| Feature             | Design Direction                               |
| ------------------- | ---------------------------------------------- |
| WASM Output         | Lean, no-closure, WASM-first codegen           |
| JVM Output          | Optional target via transpilation or IR layer  |
| Namespaces/Packages | Cleanly isolate modules and prevent collisions |
| Gradle Tooling      | Drives both targets via plugin                 |

---

## ✅ Glyph Package / Namespace Model

### 🔹 Syntax

We’ll use a `package` directive at the top of each file:

```glyph
package com.example.math

fun int add(int a, int b) {
  a + b
}
```

This:

* Defines a **module namespace** (affects compiled symbols)
* Organizes imports (future use)
* Helps align JVM class structure

### 🔹 Import Syntax (planned)

```glyph
import com.example.math.add

fun void main() {
  print(add(2, 3))
}
```

---

## ✅ Project Layout with Packages

```
/glyph-math-project
├── build.gradle
├── settings.gradle
├── src/
│   ├── main/
│   │   ├── glyph/
│   │   │   └── com/
│   │   │       └── example/
│   │   │           ├── math/
│   │   │           │   └── ops.gly      # package com.example.math
│   │   │           └── app/
│   │   │               └── main.gly     # package com.example.app
│   └── test/
│       └── glyph/
│           └── com/example/app/main_test.gly
├── build/
│   ├── wasm/
│   │   └── main.wasm
│   ├── jvm/
│   │   └── classes/
│   │       └── com/example/...
```

---

## ✅ WASM Output Design

### 🔹 Compilation Strategy

* Each `fun` becomes a top-level WASM function.
* Namespaced via mangling or type index:

    * `com.example.math.add` → `\$com_example_math_add`
* Memory layout:

    * Linear memory for arrays/maps
    * Global offset tracking for malloc-like allocator

### 🔹 Sample WAT

```wasm
(module
  (type \$t0 (func (param i32 i32) (result i32)))
  (func \$com_example_math_add (type \$t0)
    local.get 0
    local.get 1
    i32.add
  )
  (func \$main ...)
  (export "main" (func \$main))
)
```

> Strings, maps, records would use a minimal runtime with linear memory and offsets.

---

## ✅ JVM Output Design

### 🔹 Transpilation Strategy

* Glyph functions map to static methods
* Packages become class paths
* `record` maps to Java/Kotlin-style data classes
* Function types → `java.util.function.*` or custom interfaces

### 🔹 Example JVM Output (from `com.example.math.ops.gly`)

```java
package com.example.math;

public class Ops {
    public static int add(int a, int b) {
        return a + b;
    }
}
```

### 🔹 Function Types in JVM

```java
import java.util.function.BiFunction;

BiFunction<Integer, Integer, Integer> add = Ops::add;
```

Or generate:

```java
@FunctionalInterface
public interface IntBinary {
    int apply(int a, int b);
}
```

---

## ✅ Compiler Targets and Flags

| Target | Output                   | Command Example                          |
| ------ | ------------------------ | ---------------------------------------- |
| WASM   | `.wasm`, `.wat`          | `glyphc --target=wasm src/main.glyph`    |
| JVM    | `.class`, `.jar`, source | `glyphc --target=jvm src/main.glyph`     |
| IR     | Optional `.ir.json`      | For debugging, transpilation, or plugins |

---

## 🧾 Summary of Package + Output Model

| Component        | Glyph Example                              |
| ---------------- | ------------------------------------------ |
| Package          | `package com.example.util`                 |
| File Path        | `src/glyph/com/example/util/tools.gly`     |
| WASM Func Name   | `\$com_example_util_add`                    |
| JVM Class Output | `com.example.util.Tools`                   |
| Function Types   | `type Binary = fun(int, int): int`         |
| Gradle Targets   | WASM (`main.wasm`), JVM (`.class`, `.jar`) |

