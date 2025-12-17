---
title = "File I/O"
layout = "default"
---

# 📁 Glyph Standard Library: File I/O API

## 🎯 Goals

* Async by default — all I/O operations return `Future[T]` and require `await`
* Record-based configuration for extensibility and clarity
* Optional fluent convenience methods for common patterns
* Compatible with platforms that support filesystem access:

    * ✅ **JVM**: full access
    * ✅ **WASI**: partial access (if enabled)
    * ❌ **Cloudflare**: not supported (compile error)

---

## ✅ Usage Model

### 🔸 Base namespace:

```glyph
filesystem.request(cfg)
filesystem.read.request(cfg)
filesystem.write.request(cfg)
filesystem.append.request(cfg)
filesystem.exists.request(cfg)
filesystem.delete.request(cfg)
```

---

## 🧾 Record Definitions

### 🔹 `FileRequest`

```glyph
record FileRequest {
  string path
  string? content       // used for write/append
  bool binary           // false = UTF-8 text; true = raw bytes (future)
}
```

### 🔹 `FileReadResponse`

```glyph
record FileReadResponse {
  string? content
  bool found
}
```

### 🔹 `FileWriteResponse`

```glyph
record FileWriteResponse {
  bool success
  int bytesWritten
}
```

---

## ✍️ Example: Read a File

```glyph
val FileRequest cfg = FileRequest {
  path = "/tmp/data.txt"
  binary = false
}

val FileReadResponse result = await filesystem.read.request(cfg)

if result.found {
  println("File says: " + (result.content ?: "empty"))
} else {
  println("File not found")
}
```

---

## ✍️ Example: Write a File

```glyph
val cfg = FileRequest {
  path = "/tmp/log.txt"
  content = "Started at 9:01"
  binary = false
}

val FileWriteResponse result = await filesystem.write.request(cfg)

if result.success {
  println("Wrote " + result.bytesWritten + " bytes.")
} else {
  println("Failed to write file.")
}
```

---

## ✅ Fluent Shortcuts (Optional)

To improve ergonomics:

```glyph
val result = await filesystem.read.request("/tmp/user.json")

val ok = await filesystem.write.request("/tmp/log.txt", "hello world")

val exists = await filesystem.exists.request("/tmp/config.yaml")
```

Each shortcut would wrap the `FileRequest` object internally.

---

## ⚠ Platform Behavior

| Platform   | Behavior                                         |
| ---------- | ------------------------------------------------ |
| JVM        | Maps to `java.nio.file.Files` and friends        |
| WASI       | Uses WASI syscalls (`fd_read`, `fd_write`, etc.) |
| Cloudflare | ❌ File I/O not supported → compile error         |

> Glyph compiler emits a **platform-aware error** if file APIs are used on unsupported targets (e.g., Cloudflare).

---

## ✅ Summary of File I/O Design

| Operation    | Function Call                          | Input         | Output Type         |
| ------------ | -------------------------------------- | ------------- | ------------------- |
| Read file    | `await filesystem.read.request(cfg)`   | `FileRequest` | `FileReadResponse`  |
| Write file   | `await filesystem.write.request(cfg)`  | `FileRequest` | `FileWriteResponse` |
| Append file  | `await filesystem.append.request(cfg)` | `FileRequest` | `FileWriteResponse` |
| Check exists | `await filesystem.exists.request(cfg)` | `FileRequest` | `bool`              |
| Delete file  | `await filesystem.delete.request(cfg)` | `FileRequest` | `bool`              |


```glyph
fun async FileReadResponse filesystem.read.request(string path)

fun async FileWriteResponse filesystem.write.request(string path, string content)

fun async bool filesystem.exists.request(string path)

fun async bool filesystem.delete.request(string path)
```

Each one internally builds a `FileRequest` and delegates to the generic version:

```glyph
fun async FileReadResponse filesystem.read.request(FileRequest cfg)
```

---

### ✅ Bonus: Overload Pattern

This also gives you freedom later to support overloads:

```glyph
fun async FileWriteResponse filesystem.write.request(FileRequest cfg)
fun async FileWriteResponse filesystem.write.request(string path, string content)
fun async FileWriteResponse filesystem.write.request(string path, string content, bool binary)
```
