

## ✅ 1. **Fluent Protocol API Design**

You define a hierarchy of modules and functions like:

```glyph
network.http.request(cfg)
network.http.get.request(cfg)
network.websocket.request(cfg)
network.custom.request(cfg)
```

This makes `network` the gateway, with submodules for each protocol.

Each `request(...)` accepts a **record** that defines the call.

---

## ✅ 2. **Record-Based Request Configuration**

### 🔸 Example: `HttpRequest`

```glyph
record HttpRequest {
  string method       // "GET", "POST", etc.
  string url
  [string: string] headers
  string? body
  int timeout         // in ms
}
```

You might define similar records for `WebSocketRequest`, `WebRTCOffer`, etc.

### Example usage:

```glyph
val HttpRequest cfg = HttpRequest {
  method = "POST"
  url = "https://api.example.com"
  headers = [string: string] {
    "Content-Type": "application/json"
  }
  body = "{ \"id\": 1 }"
  timeout = 5000
}
```

---

## ✅ 3. **Async-Friendly Request + Response Handling**

### 🔸 Suggested async model:

* `request(cfg)` returns a **`Future[HttpResponse]`**
* You `await` it to extract the response

### 🔸 Example response record:

```glyph
record HttpResponse {
  int status
  string? body
  [string: string] headers
}
```

---

### ✍️ Sample Full Usage

```glyph
val HttpRequest cfg = HttpRequest {
  method = "GET"
  url = "https://news.api.com/latest"
  headers = [:]
  timeout = 3000
}

val HttpResponse resp = await network.http.request(cfg)

if resp.status == 200 {
  println(resp.body ?: "No content")
} else {
  println("Failed with code " + resp.status)
}
```

Or use convenience call:

```glyph
val resp = await network.http.get.request(HttpRequest {
  url = "https://api.site.com"
  timeout = 2000
  headers = [:]
})
println(resp.status)
```

---

## 🔄 Fluent Convenience Functions

Under the hood:

```glyph
network.http.get.request(cfg)
```

...could just fill in `method = "GET"` and delegate to:

```glyph
network.http.request(cfg)
```

Same for `POST`, etc.

---

## ✅ Avoiding Callback Hell

Instead of:

```glyph
network.http.request(cfg, callback)  // ❌ JS-style
```

You're doing:

```glyph
val resp = await network.http.request(cfg)  // ✅ async by default
```

No need to nest or chain — just linear flow with `await`.

---

## 🧠 Optional: Future Extension

* `spawn` and `await` would allow advanced use later:

  ```glyph
  val task = spawn { network.http.request(cfg) }
  val result = await task
  ```

* Streaming support (WebSocket, etc.) could follow this shape:

  ```glyph
  val stream = await network.websocket.request(wsCfg)
  stream.onMessage { msg -> ... }     // event registration
  ```

---

## ✅ Summary of Design

| Concept             | Syntax Example                                      |
| ------------------- | --------------------------------------------------- |
| Base request        | `await network.http.request(cfg)`                   |
| GET shortcut        | `await network.http.get.request(cfg)`               |
| Configurable record | `HttpRequest { method = ..., url = ..., ... }`      |
| Async response      | `val resp = await ...`                              |
| Response object     | `HttpResponse { status, body, headers }`            |
| Future-proof design | Open to custom protocols (`network.custom.request`) |

