# JSON helper

`pmx-kt` ships a tiny, dependency-free JSON parser/encoder used by the
`SERIALIZE` / `DESERIALIZE` opcodes. It is exposed as
`com.lenerotex.pmx.json.Json` so you can use it in host code too — handy
when the rest of your stack needs to stay zero-dep, or when you're just
bridging a native to whatever JSON your service speaks.

> Want to use Jackson / kotlinx.serialization / Moshi for the
> `SERIALIZE` / `DESERIALIZE` opcodes themselves? See the
> [Custom JSON codec](serialize.md#custom-json-codec) section in the
> serialize guide — `Json` is the default, but pluggable.

```kotlin
package com.lenerotex.pmx.json

object Json {
    fun encode(value: Any?): String           // deterministic, key-order preserved
    fun decode(text: String): Any?            // any JSON value at top level
}
```

The decoder accepts any JSON value (object, array, string, number,
boolean, null) at the top level — not just objects.

## Encoding

| Kotlin in              | JSON out                                          |
|------------------------|---------------------------------------------------|
| `null`                 | `null`                                            |
| `Boolean`              | `true` / `false`                                  |
| `Byte`/`Short`/`Int`/`Long` | integer (no exponent)                        |
| `Double`/`Float`       | shortest round-trip number; `NaN`/`Inf` → `null`  |
| `CharSequence`/`Char`  | escaped JSON string                               |
| `Map<*, *>`            | object (keys via `toString()`; `null` key → `"null"`); insertion order preserved |
| `Iterable<*>`/`Array<*>`/`IntArray`/`LongArray`/`DoubleArray`/`FloatArray`/`BooleanArray` | array |
| anything else          | `toString()` quoted as a JSON string              |

Object keys are written in iteration order, so a `LinkedHashMap` gives
stable output:

```kotlin
import com.lenerotex.pmx.json.Json

val payload = linkedMapOf(
    "kills"  to 7L,
    "deaths" to 3L,
    "alive"  to true,
    "name"   to "Ada",
)

println(Json.encode(payload))
// {"kills":7,"deaths":3,"alive":true,"name":"Ada"}
```

Nested structures work as expected:

```kotlin
val resp = mapOf(
    "ok" to true,
    "rows" to listOf(
        mapOf("id" to 1L, "name" to "Ada"),
        mapOf("id" to 2L, "name" to "Grace"),
    ),
)

Json.encode(resp)
// {"ok":true,"rows":[{"id":1,"name":"Ada"},{"id":2,"name":"Grace"}]}
```

`NaN` and `Infinity` are not legal JSON, so the encoder writes them as
`null`:

```kotlin
Json.encode(Double.NaN)            // "null"
Json.encode(Double.POSITIVE_INFINITY)  // "null"
```

## Decoding

```kotlin
val parsed = Json.decode("""{"a": 1, "b": [true, null, "x"]}""")
println(parsed)
// {a=1, b=[true, null, x]}
```

- Objects map to `LinkedHashMap<String, Any?>`.
- Arrays map to `ArrayList<Any?>`.
- Integer-shaped numbers (no `.`, no `e`/`E`) return `Long` if they fit,
  otherwise `Double`. Numbers with a decimal or exponent always return
  `Double`. (Mirrors what the VM expects when storing into `cell` /
  `float` cells.)
- Strings decode `\"`, `\\`, `\/`, `\b`, `\f`, `\n`, `\r`, `\t`, and
  `\uXXXX` escapes.

Bad input throws `IllegalArgumentException` at the offending offset:

```kotlin
try {
    Json.decode("{")
} catch (e: IllegalArgumentException) {
    println(e.message)        // json: unterminated object at offset 1
}
```

## Wiring it into a native

A typical pattern: the script hands you JSON, your native parses it,
fetches some data, and returns JSON back:

```kotlin
import com.lenerotex.pmx.*
import com.lenerotex.pmx.json.Json

vm.registerNative("HttpProxy") { args ->
    val req = Json.decode(args[0] as String) as Map<*, *>
    val resp = httpClient.execute(
        method = req["method"] as String,
        url    = req["url"]    as String,
        body   = req["body"]   as String?,
    )
    Json.encode(
        mapOf(
            "status" to resp.status.toLong(),
            "body"   to resp.body,
        )
    )
}
```

If you'd rather hand the script a struct directly, use a struct ref
parameter — see [Reference parameters → struct refs](references.md#struct-references).
