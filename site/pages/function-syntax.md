
## ✅ Revised Glyph Function Syntax

### 🔸 Function Definition Format

To align with variable declarations (`val int x = 5`), we'll move the return type **before** the function name:

```glyph
fun int add(int a, int b) {
  return a + b
}
```

### 🔸 Structure Breakdown

```
fun <return_type> <name>(<typed_params>) { ... }
```

| Element         | Required? | Notes                                                     |
| --------------- | --------- | --------------------------------------------------------- |
| `fun`           | ✅         | Function keyword                                          |
| `<return_type>` | ✅         | Must be explicit (like all types in Glyph)                |
| `<name>`        | ✅         | Function name                                             |
| `<params>`      | ✅         | Typed parameters (no inference, like variables)           |
| `{}` block      | ✅         | Contains function body; return is optional (Kotlin-style) |

---

## ✅ Return Behavior

Glyph will mimic **Kotlin-style returns**:

* **Explicit `return`** is allowed
* **Last expression** in the function block is used as the return value (if compatible with return type)
* If return type is `void`, either:

    * Use `return` without value
    * Omit return statement altogether

### Examples:

```glyph
fun int square(int x) {
  x * x    // implicit return
}

fun void greet(string name) {
  print("Hello, " + name)  // no return needed
}
```

If a function has multiple branches, you must **use `return`** explicitly in each branch.

---

## ✅ Null Safety: Kotlin vs Glyph

### 🔹 Kotlin's Null Safety Model

In Kotlin:

* **Non-null by default**: `String` cannot be null
* Use `?` for nullable types: `String?`
* Compiler enforces null checks

```kotlin
val name: String = "Alice"     // never null
val nickname: String? = null   // nullable
```

Accessing nullable types safely:

```kotlin
nickname?.length           // safe call
nickname?.let { println(it) } // executes block if not null
val len = nickname ?: 0    // Elvis operator (default fallback)
```

---

### 🔹 Glyph Null Safety Proposal

#### ✅ Non-null by default

All types are non-null unless declared otherwise using `?` suffix:

```glyph
val string name = "Alice"       // never null
val string? nickname = null     // nullable
```

#### ✅ Type Rules

* `string` ≠ `string?`
* Must perform explicit null checks or use safe-access operators

#### ✅ Safe Access Operators (planned)

* `?.` → safe property or method call
* `?:` → Elvis operator for fallback

```glyph
val int? x = null
val int y = x ?: 0         // y = 0 if x is null

val int? len = nickname?.length
```

---

### 🔸 Compiler Enforcement

* Disallows passing `string?` to a function expecting `string`
* Forces null-check or Elvis fallback

```glyph
fun void greet(string name) {
  print("Hello, " + name)
}

greet(nickname)    // ❌ compile error if nickname is string?
greet(nickname ?: "friend")  // ✅
```

---

## 🧾 Updated Grammar Snippet

```ebnf
function_decl ::= "fun" type IDENT "(" param_list? ")" block
param         ::= type IDENT
type          ::= base_type ["?"]    // supports nullability suffix
```

---

## ✅ Summary

| Feature          | Kotlin                | Glyph                |
| ---------------- | --------------------- | -------------------- |
| Nullability      | `Type?`               | `type?`              |
| Safe access      | `?.`                  | `?.` (planned)       |
| Default fallback | `?:`                  | `?:` (planned)       |
| Return behavior  | Last expr or `return` | Same                 |
| Function syntax  | `fun name(): Type`    | `fun Type name(...)` |


## ✅ `async` Keyword — Asynchronous Function Support

Glyph uses the `async` keyword to define functions that perform asynchronous operations and must be resolved using `await`.

---

### 🔹 Function Declaration Syntax

```glyph
fun async <return_type> <function_name>(<params>) {
  // function body
}
```

* `async` appears **before the return type**
* The return type reflects the **actual result** of the function (not a future)
* The function must be **invoked with `await`** to access its result

---

### 🔸 Example

```glyph
fun async HttpResponse fetchData(string url) {
  val HttpRequest req = HttpRequest {
    method = "GET"
    url = url
    headers = [:]
    timeout = 3000
  }

  return await network.http.request(req)
}
```

```glyph
val HttpResponse result = await fetchData("https://api.site.com")
println(result.status)
```

---

### 🔸 Rules

| Rule                              | Description                                              |
| --------------------------------- | -------------------------------------------------------- |
| ✅ `await` required                | You must `await` the result of an `async` function call  |
| ❌ Cannot `await` a non-async func | Compile error if `await` is used on a non-async function |
| ✅ `async` required to suspend     | Only `async` functions can use `await` internally        |
| ✅ Return type is not wrapped      | The declared type is the resolved value, not a future    |

---

### 🔹 Common Pattern

```glyph
fun async string loadGreeting() {
  val response = await filesystem.read.request("/tmp/greeting.txt")
  return response.content ?: "Hello"
}

val message = await loadGreeting()
println(message)
```

---

### 🔸 Syntax Recap (Grammar)

```ebnf
function_decl ::= "fun" ["async"] type IDENT "(" param_list? ")" block
```

---

### ✅ Summary

| Keyword | Applies To | Purpose                              |
| ------- | ---------- | ------------------------------------ |
| `async` | Function   | Declares an asynchronous function    |
| `await` | Expression | Resolves the result of an async call |

---

This pattern provides **clean async flow** in Glyph without callbacks, promises, or explicit future types — making asynchronous programming **safe, readable, and first-class**.

