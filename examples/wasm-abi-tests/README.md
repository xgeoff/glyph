# wasm-abi-tests

This mini project exercises the Glyph WASM runtime ABI with focused scripts.

## How to run

Inside this directory:

```bash
./gradlew glyphParse          # parse + type-check all sources
./gradlew glyphCompileWasm    # emit build/wasm/main.wasm
./gradlew glyphCompileWasm -PglyphEmitWat=true   # also emit main.wat
```

Set `GRADLE_USER_HOME` if you want a local cache:

```bash
GRADLE_USER_HOME=$PWD/.gradle-cache ./gradlew glyphCompileWasm
```

## Contents

* `record_tests.gly` — record literal + field access
* `array_tests.gly` — heap array allocation, indexing, mutation
* `map_control_tests.gly` — map literal, get/set, ternary, elvis, match
* `main.gly` — calls each suite; this is the `glyph` entry point
