# Serialize, deserialize & structs

PX has a small struct system that the runtime can shuffle to and from
JSON. Three surface areas:

1. The PX intrinsics — `serialize` / `deserialize` (object form),
   `serialize(arr)` / `deserialize(arr)` (whole-array form), and
   `getJsonTag`. Compiled directly into bytecode by `pxc`.
2. The host helpers — `Script.serializeStruct(name, base)` and
   `Script.getJsonTag(name, entry)`.
3. Native struct references — see [Reference parameters → struct refs](references.md#struct-references).

## A canonical struct

```pawn
struct pInfo {
    pKills  "kills",
    pDeaths "deaths"
};

new PlayerInfo[MAX_PLAYERS][pInfo];
```

Per [`spec/LANGUAGE.md`](../../spec/LANGUAGE.md), the string after the
entry name is the struct's "JSON tag" — what the runtime uses as the
key when serializing.

## In-script: `serialize` / `deserialize`

Inside `.px`:

```pawn
public dump() {
    return serialize(PlayerInfo[0]);     // -> "{"kills":7,"deaths":3}"
}

public dumpAll() {
    return serialize(PlayerInfo);        // -> "[{...}, {...}, ...]"
}

public load(payload) {
    deserialize(PlayerInfo[0], payload);          // lenient: missing keys skipped
    deserialize(PlayerInfo[0], payload, true);    // strict: throws on missing key
    return 1;
}

public loadAll(payload) {
    deserialize(PlayerInfo, payload);             // lenient (writes min(payload.length, N) rows)
    return 1;
}
```

The Kotlin VM reproduces the TS semantics exactly:

| Mode    | `validate` | Behaviour                                                                 |
|---------|------------|---------------------------------------------------------------------------|
| Lenient | `false`/0  | Missing keys leave the corresponding cell unchanged.                      |
| Strict  | `true`/1   | Missing key throws `IllegalStateException("pmx: deserialize: missing key '...'")` |

For arrays:

| Mode    | `validate` | Behaviour                                                                                  |
|---------|------------|--------------------------------------------------------------------------------------------|
| Lenient | `false`/0  | Writes `min(payload.size, declaredLength)` rows; extra payload rows dropped, trailing PX rows untouched. |
| Strict  | `true`/1   | Requires `payload.size == declaredLength` and every key on every row.                        |

## Host-side: `Script.serializeStruct`

Build a `Map<String, Any?>` from a flat struct row sitting in the heap
at a given base address. Useful when you have the bytes already loaded
and want to inspect or pass them on as JSON without going through PX
code.

```kotlin
val (size, base) = script.globalLayout()[0]   // first global is PlayerInfo[0]
val obj = script.serializeStruct("pInfo", base)
println(obj)                                  // {kills=7, deaths=3}
```

To dump every row of a 2-D global:

```kotlin
val struct = "pInfo"
val (size, base) = script.globalLayout()[0]
val entryCount = 2     // matches the struct's entry count
val rows = (0 until size step entryCount).map { offset ->
    script.serializeStruct(struct, base + offset)
}
```

## `getJsonTag`

The PX-side `getJsonTag(StructName[entryName])` is rewritten by the
compiler into a single `PUSH_CONST` of the resolved tag string. Zero
runtime cost.

```pawn
struct pHubSpot {
    pApiKey : string  "apiKey",
    pSecret : string  "secret",
    pNoTag                          // no JSON tag declared
};

main() {
    new s = getJsonTag(pHubSpot[pApiKey]);   // "apiKey"
    printf("%s", getJsonTag(pHubSpot[pSecret])); // "secret"
    return s == "apiKey";
}
```

The same lookup is exposed on `Script` for tooling that needs it at
runtime:

```kotlin
script.getJsonTag("pHubSpot", "pApiKey")    // "apiKey"
script.getJsonTag("pHubSpot", "pNoTag")     // "pNoTag" (entry name fallback)
script.getJsonTag("pHubSpot", "missing")    // null
script.getJsonTag("noSuchStruct", "x")      // null
```

## Round-trip example

```kotlin
import com.lenerotex.pmx.*
import java.io.File

val vm = VM().also { registerBuiltins(it) }
val script = loadPmx(File("dump.pmx"), vm)

// 1. Read existing globals
val (_, base) = script.globalLayout()[0]
val before = script.serializeStruct("pInfo", base)
println("before: $before")

// 2. Run a script entry that mutates the struct
script.callPublic("AwardKill", listOf(0L))   // increments PlayerInfo[0][pKills]

val after = script.serializeStruct("pInfo", base)
println("after: $after")

// 3. Ask the script to dump everything as JSON via the PX intrinsic
val dump = script.callPublic("dumpAll", emptyList()) as String
println(dump)
```

## Type coercion at struct boundaries

The same coercion rules as for [reference parameters](references.md#type-coercion-on-writeback)
apply when reading from / writing to a struct entry. So if a JSON
payload has `"kills": "7"`, the deserializer will write `7L` into the
`cell`-typed entry, mirroring the TS runtime's behaviour.

## Custom JSON codec

`SERIALIZE` / `DESERIALIZE` go through a pluggable
[`JsonCodec`](../src/main/kotlin/com/lenerotex/pmx/json/JsonCodec.kt) on
`VMOptions`. By default the bundled zero-dep `Json` is used
(`DefaultJsonCodec`); swap in Jackson, kotlinx.serialization, Moshi,
gson — anything — by providing your own implementation:

```kotlin
package com.lenerotex.pmx.json

interface JsonCodec {
    fun encode(value: Any?): String
    fun decode(text: String): Any?
}
```

### Contract

- `encode` receives a tree of `Map<String, Any?>` / `List<Any?>` /
  scalars (`Long`, `Double`, `Boolean`, `String`, `null`). It must
  return a self-contained JSON string.
- `decode` receives a JSON string. It must return the same shape: a
  `Map<String, Any?>` for objects, a `List<Any?>` for arrays, or a
  scalar for top-level scalars.

If `decode` throws anything, the VM rethrows it as
`IllegalStateException("pmx: deserialize: invalid JSON payload (<message>)")`
with the original cause attached — same shape today's tests expect.

### Wiring

At construction:

```kotlin
val vm = VM(VMOptions(jsonCodec = MyCodec))
```

…or at runtime:

```kotlin
vm.options.jsonCodec = MyCodec
```

The change applies to every subsequent `start()` / `callPublic()` /
`SERIALIZE` / `DESERIALIZE` on every `Script` bound to that `VM`.

### Jackson

Add `com.fasterxml.jackson.module:jackson-module-kotlin` to your host
project (not `pmx-kt` — the library stays zero-dep), then:

```kotlin
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lenerotex.pmx.*
import com.lenerotex.pmx.json.JsonCodec

class JacksonCodec(private val mapper: ObjectMapper = jacksonObjectMapper()) : JsonCodec {
    override fun encode(value: Any?): String = mapper.writeValueAsString(value)
    override fun decode(text: String): Any? = mapper.readValue(text, Any::class.java)
}

val vm = VM(VMOptions(jsonCodec = JacksonCodec()))
```

Jackson's default mapping returns `LinkedHashMap<String, Object>` for
objects, `ArrayList<Object>` for arrays, and `Integer`/`Long`/`Double`
for numbers — all already handled by the VM's `coerceValue` shim.
For very large integers Jackson may return `BigInteger`; if you need
that to land in a `cell`, pre-configure the mapper with
`DeserializationFeature.USE_LONG_FOR_INTS` (or coerce in your codec).

### kotlinx.serialization

```kotlin
import com.lenerotex.pmx.*
import com.lenerotex.pmx.json.JsonCodec
import kotlinx.serialization.json.*

class KxSerializationCodec(private val format: Json = Json) : JsonCodec {
    override fun encode(value: Any?): String = format.encodeToString(JsonElement.serializer(), toJsonElement(value))
    override fun decode(text: String): Any? = fromJsonElement(format.parseToJsonElement(text))

    private fun toJsonElement(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is Map<*, *> -> JsonObject(v.entries.associate { (k, vv) -> k.toString() to toJsonElement(vv) })
        is Iterable<*> -> JsonArray(v.map { toJsonElement(it) })
        else -> JsonPrimitive(v.toString())
    }

    private fun fromJsonElement(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.boolean
            e.longOrNull != null -> e.long
            else -> e.double
        }
        is JsonObject -> e.mapValues { fromJsonElement(it.value) }
        is JsonArray -> e.map { fromJsonElement(it) }
    }
}

val vm = VM(VMOptions(jsonCodec = KxSerializationCodec()))
```

### When you'd want this

- You already have a configured Jackson `ObjectMapper` with custom
  modules / serializers and want one canonical JSON representation
  across host and script.
- You need consistent number handling (`BigDecimal`, `BigInteger`,
  configured precision) across your service.
- You want to swap to a JSON-with-comments / JSON5 / YAML codec for
  certain workflows without touching the VM.
- You want pretty-printing in dev and minified output in prod.

The default `DefaultJsonCodec` stays in place — you only opt in when
you need to.
