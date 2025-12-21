---
title = "Glyph CLI Reference"
layout = "default"
---

## 🔹 Overview

`glyph-cli` lives under `tools/glyph-cli/` and provides a lightweight way to parse, type‑check, and interpret Glyph programs straight from the command line. It shares the same parser and interpreter that the Gradle plugin uses, so behaviour is consistent.

Run it with Go:

```bash
go run ./tools/glyph-cli --file examples/wasm-abi-tests/src/main/glyph/com/example/abi/main.gly
```

If you install it (`go install ./tools/glyph-cli`), invoke it as `glyph-cli`.

---

## 🔹 Command Usage

```
glyph-cli [options] [file.gly]
```

### Options

| Flag              | Description                                                                 |
| ----------------- | --------------------------------------------------------------------------- |
| `--file <path>`   | Path to a `.gly` file (positional filename works too)                      |
| `--root <dir>`    | Project root directory (defaults to the source file’s directory)           |
| `-e "<code>"`     | Execute inline Glyph code without creating a file                          |
| `--run-wasm`      | Run the compiled `.wasm` via `wasmtime` (requires `--file path/to/main.wasm`) |
| `--help`, `-h`    | Display usage and exit                                                      |

### Examples

Run a file inside `examples/`:

```bash
glyph-cli --file examples/advanced-features/src/main/glyph/com/example/advanced/app/main.gly
```

Execute a snippet inline:

```bash
glyph-cli -e 'fun void main() { print("hi from inline code") }'
```

Execute a WASM module that was produced by the Gradle plugin (requires `wasmtime`):

```bash
glyph-cli --file build/wasm/main.wasm --run-wasm
```

### Error Reporting

All errors are prefixed with `Error:` and, when available, include the file or inline snippet where the problem originated. Type or runtime failures in the interpreter show up the same way they would via the Gradle tasks.

---

## 🔹 Tips

* Use `--root` when your entry file lives outside the current directory so imports resolve correctly.
* Inline execution (`-e`) is ideal for quick experiments or regression repros—no temp files necessary.
* `--run-wasm` is currently a thin wrapper around `wasmtime`. If the command isn’t installed or fails, the CLI prints a helpful hint.
