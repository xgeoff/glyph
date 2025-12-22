# Closure Playground

This project exercises Glyph’s closure semantics by nesting multiple
lambdas that capture outer variables (and other lambdas) inside a Gradle
example that uses the Glyph plugin.

## Layout

```
examples/closure-playground/
 ├─ build.gradle
 ├─ settings.gradle
 ├─ gradlew                 # thin wrapper that reuses the repo root wrapper
 └─ src/main/glyph/com/example/closures/main.gly
```

## Running with Gradle

```
cd examples/closure-playground
./gradlew glyphParse
./gradlew glyphCompileWasm
```

## Running via CLI

```
go run ./tools/glyph-cli \
    examples/closure-playground/src/main/glyph/com/example/closures/main.gly \
    --root examples/closure-playground
```

The script prints the results of three closure pipelines (calibration,
signal processing, nested booster) that all reuse a captured
`globalOffset`. Use it as a regression test whenever modifying closure
lowering.
