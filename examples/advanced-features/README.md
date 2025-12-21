# Advanced Glyph Feature Demo

This sample lives inside the repository at
`examples/advanced-features` and shows off the newer
multi-package + multi-file capabilities that were just wired into the
Gradle plugin.

Highlights:

- Packages + imports split across `model`, `data`, `analytics`, `util`, and `app`
- Record literals with nullable fields and nested records
- Array and map allocation + mutation
- `match`, `if`, ternary, and Elvis expressions
- Null-safe field access (`?.`) flowing into user-visible output

## Running it

```
cd examples/advanced-features
./gradlew glyphParse
./gradlew glyphCompileWasm
./gradlew glyphCompileJar
```

Each Gradle task now indexes the entire `src/main/glyph` tree so the
imports resolve automatically.
