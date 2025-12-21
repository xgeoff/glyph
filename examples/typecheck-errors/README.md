# Type Checker Error Samples

This directory contains small `.gly` programs that intentionally fail type checking. They are handy when validating the compiler or while iterating on new diagnostics.

| File | Expected error |
| --- | --- |
| `missing-return.gly` | Non-void function without a return on every path |
| `wrong-arg-count.gly` | Calling a function with too few arguments |
| `field-not-found.gly` | Accessing a record field that does not exist |
| `if-not-bool.gly` | Using a non-boolean expression in an `if` condition |
| `assignment-type-error.gly` | Assigning a mismatched type to a variable |

To exercise a specific error, point your Gradle example’s `glyph.entryFile` at one of these files (or copy the snippet into your project) and run:

```bash
./gradlew -PglyphDebug=true glyphParse
```

Enabling `glyphDebug` logs the indexed symbols/imports, which helps trace resolution problems alongside the type error message.
