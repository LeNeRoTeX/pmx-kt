# Calling publics & main

`Script` exposes two ways to run script code:

```kotlin
fun start(): Any                                          // runs main() if present
fun callPublic(name: String, args: List<Any?> = emptyList()): Any
```

## `start()`

Calls the public named `main` if the module exports one; otherwise
returns `0L`. The return type is `Any` so it can be `Long`, `Double`,
or `String` depending on what the script returns.

```kotlin
val ret = script.start()
println(ret)                  // e.g. "1" or 42L
val asLong = ret as? Long ?: 0L
```

## `callPublic`

Invoke any entry in the module's public table by name. Arguments are
passed as a `List<Any?>`; you can mix value-shaped args and `Ref` boxes
to match the public's declared signature.

```kotlin
script.callPublic("OnPlayerConnect", listOf(42L))

val score: Long = script.callPublic("ComputeScore", listOf(10L, 20L)) as Long
```

Argument count must match the public's declared arity, and ref/value
shape must match each parameter — passing a `Long` where the script
declared `&out: cell` (or vice versa) throws
`IllegalArgumentException`.

### Cell representations

PX cells map onto these Kotlin types:

| PX type   | Kotlin runtime |
|-----------|----------------|
| `CELL`    | `Long`         |
| `BOOL`    | `Long` (0 or 1) |
| `FLOAT`   | `Double`       |
| `STRING`  | `String`       |

Coercion happens at every parameter boundary (`callPublic` arguments,
`callPublic` ref writebacks, `NCALL` arguments, struct entry reads /
writes), so you can pass an `Int` to a `cell` parameter and the VM will
widen it to `Long` for you. The same applies to ref writeback: if a
script wrote a `Long` into a `&out: string` cell, `Ref.value` ends up
as a `String` after `callPublic` returns.

### Mixed value + ref args

```kotlin
val out = Ref("")
val ok = script.callPublic("OnReceive", listOf(payload, out)) as Long
println(out.value)    // populated by `output = serialize(...)` in the script
```

See [Reference parameters](references.md) for the full `Ref` matrix.

## Inspecting state

The script exposes a small read-only surface for tests and tooling:

```kotlin
fun readCell(addr: Int): Any?
fun globalLayout(): List<Pair<Int, Int>>     // (size, base) per global, in declaration order
fun serializeStruct(name: String, base: Int): StructValue
fun getJsonTag(structName: String, entryName: String): String?
```

Example — read the first global as a single cell:

```kotlin
val (_, base) = script.globalLayout()[0]
println(script.readCell(base))     // 0L by default
```

Example — serialize a struct row sitting at a known global base:

```kotlin
val (_, base) = script.globalLayout()[0]
val obj = script.serializeStruct("pInfo", base)
println(obj)        // {kills=7, deaths=3}
```

See [Serialize & structs](serialize.md) for more.

## Exception flow

When script code calls the built-in `exception(code, msg)`, the VM
unwinds and `callPublic` re-throws a [`PmxScriptException`](errors.md).
Other runtime failures (decoder, decryption, instruction limit) surface
as `IllegalStateException`.

```kotlin
try {
    script.callPublic("DangerousOp", listOf("payload"))
} catch (e: PmxScriptException) {
    println("script aborted: code=${e.code} msg=${e.message}")
} catch (e: IllegalStateException) {
    println("VM/runtime failure: ${e.message}")
}
```
