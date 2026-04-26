# Registering natives

A "native" is a host function the script can call as if it were any other
function. Natives are registered on the `VM`, **not** on individual
scripts, so you can share a registry across many loaded modules.

## Signature

```kotlin
typealias NativeFn = (args: List<Any?>) -> Any?

fun VM.registerNative(name: String, fn: NativeFn)
```

- `args` is positional; `args[0]`, `args[1]`, … match the script's
  declared parameter list.
- The element type depends on the declared parameter type:
  - `cell` / `bool` → `Long`
  - `float`         → `Double`
  - `string`        → `String`
  - `& cell`/`& bool`/`& float`/`& string` → `Ref<Any?>`
    (the `value` is initially a `Long`/`Double`/`String` per the cell type)
  - `& : Struct`    → `Ref<Map<String, Any?>>` (a `StructValue`)
  - `& : Struct[]`  → `Ref<List<Map<String, Any?>>>`
- The return value is pushed as the call's result. `Long`, `Double`,
  `String`, `Boolean`, and `null` are accepted; `null` is treated as
  `0L`. See [Reference parameters](references.md) for ref details.

## Plain value natives

```kotlin
val vm = VM()

vm.registerNative("Min") { args ->
    minOf(args[0] as Long, args[1] as Long)
}

vm.registerNative("Greet") { args ->
    "Hello, ${args[0] as String}!"
}

vm.registerNative("PlayerExists") { args ->
    val pid = args[0] as Long
    if (pid in 0L..1023L) 1L else 0L
}
```

Tip: prefer the helper coercions when you don't trust the input shape:

```kotlin
import com.lenerotex.pmx.asLong
import com.lenerotex.pmx.asDouble
import com.lenerotex.pmx.stringify

// (these helpers are internal in 0.1.x; copy them or hand-roll equivalents
// until they are exposed publicly.)
```

## Reading varargs / extras

`printf`/`format` are variadic; the runtime passes the format string in
`args[0]` and the rest follow in declaration order:

```kotlin
vm.registerNative("LogF") { args ->
    val fmt = args[0] as String
    val rest = args.drop(1)
    println("LOG: " + rest.fold(fmt) { acc, x -> acc.replaceFirst("%v", x.toString()) })
    0L
}
```

## Returning structured results

Strings are common — return them directly:

```kotlin
vm.registerNative("FormatPlayerName") { args ->
    val firstName = args[0] as String
    val lastName  = args[1] as String
    "$lastName, $firstName"
}
```

For complex data the recommended pattern is a struct ref output rather
than packing into a JSON string. See
[Reference parameters → struct refs](references.md#struct-references).

## Throwing

Anything you throw inside a native unwinds back through `callPublic` /
`start()`. To produce a script-level exception that the script can catch,
throw `PmxScriptException`:

```kotlin
import com.lenerotex.pmx.PmxScriptException

vm.registerNative("DbFetch") { args ->
    val id = args[0] as String
    val row = db.find(id) ?: throw PmxScriptException(404, "no row $id")
    row.toJson()
}
```

Other exceptions are wrapped or surfaced as `IllegalStateException` —
see [Error handling](errors.md).

## Built-in natives

Call `registerBuiltins(vm)` to install seven always-available natives:

| Name        | Signature (PX)                  | Behaviour                                                      |
|-------------|----------------------------------|----------------------------------------------------------------|
| `print`     | `print(s)`                       | Writes `s` to stdout, no newline. Returns `0L`.                |
| `println`   | `println(s)`                     | Writes `s` to stdout with a trailing newline. Returns `0L`.    |
| `printf`    | `printf(fmt, ...)`               | `%d %f %s %b %%` mini-format with newline. Returns `0L`.       |
| `format`    | `format(fmt, ...) -> string`     | Same format, returns the formatted string.                     |
| `strlen`    | `strlen(s) -> cell`              | UTF-8 byte length of `s`.                                      |
| `strcat`    | `strcat(a, b) -> string`         | Plain string concatenation (use this rather than `+` in PX).   |
| `exception` | `exception(code, msg)`           | Throws `PmxScriptException(code, msg)`. Never returns.         |

Override any of them by re-registering with the same name **after**
`registerBuiltins`:

```kotlin
val vm = VM().also { registerBuiltins(it) }

vm.registerNative("println") { args ->
    log.info(args[0]?.toString().orEmpty())
    0L
}
```

## Registering many natives idiomatically

```kotlin
fun VM.bind(natives: Map<String, NativeFn>) =
    natives.forEach { (name, fn) -> registerNative(name, fn) }

vm.bind(
    mapOf(
        "DbInsert" to { args ->
            db.insert(args[0] as String, args[1] as String)
            1L
        },
        "DbFetch" to { args ->
            db.find(args[0] as String)?.toJson() ?: ""
        },
        "Now" to { _ -> System.currentTimeMillis() },
    )
)
```
