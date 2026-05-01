# pmx-kt

Kotlin/JVM port of [`pmx-vm`](../runtime) — loads, decrypts, and executes
encrypted `.pmx` v6 bytecode produced by [`pxc`](../compiler) or, natively
inside the JVM, by its sibling [`pxc-kt`](../pxc-kt) compiler.

- **Zero third-party runtime deps.** Shared binary-format primitives live in
  [`pmx-format-kt`](../pmx-format-kt); everything else is Kotlin stdlib + JDK.
  AES-256-GCM and SHA-256 come from the JDK; JSON is hand-rolled.
- **Full feature parity** with the TypeScript runtime: every opcode,
  `serialize` / `deserialize` (object + array, lenient + strict), scalar /
  struct / struct-array native refs, public-side scalar refs,
  `getJsonTag`, the seven built-in natives, instruction-limit guard.
- **Gradle wrapper**, Kotlin 2.1, JDK 17 toolchain.

For embedding the **compiler** next to the runtime (so you can compile a
`.px` source string to `.pmx` bytes at startup without shelling out to the
Go `pxc` binary), pair this module with [`pxc-kt`](../pxc-kt).

## Quick start

```kotlin
import com.lenerotex.pmx.*
import java.io.File

val vm = VM().also { registerBuiltins(it) }

vm.registerNative("SendClientMessage") { args ->
    val pid = args[0] as Long
    val msg = args[2] as String
    println("[player $pid] $msg")
    1L
}

val script = loadPmx(File("gamemode.pmx"), vm, key = System.getenv("PMX_KEY"))
script.start()
script.callPublic("OnPlayerConnect", listOf(42L))
```

### Compiling and running in one step (with `pxc-kt`)

```kotlin
import com.lenerotex.pxc.IncludeResolver
import com.lenerotex.pxc.Pxc

val src = javaClass.getResource("/scripts/main.px")!!.readText()
val bytes = Pxc.compile(source = src, key = System.getenv("PMX_KEY"))

val vm = VM().also { registerBuiltins(it) }
val script = loadPmxBytes(bytes, vm, key = System.getenv("PMX_KEY"))
script.start()
```

## Build & test

```bash
./gradlew build      # compile + run all tests
./gradlew test       # tests only
./gradlew publishToMavenLocal   # install into ~/.m2/repository
```

The wrapper downloads Gradle 8.10 on first run.

## Documentation

Topical guides — each one is self-contained with copy-pasteable examples:

| Topic                                         | Covers                                                                       |
|-----------------------------------------------|-------------------------------------------------------------------------------|
| [Installation](docs/installation.md)          | Adding `pmx-kt` to a Gradle / Maven project, GitHub Packages PAT setup        |
| [Loading scripts](docs/loading.md)            | `loadPmx`, `loadPmxBytes`, encrypted vs plaintext, key derivation, stubbed natives |
| [Calling publics & main](docs/calls.md)       | `start()`, `callPublic()`, return types, `readCell`, `globalLayout`            |
| [Registering natives](docs/natives.md)        | `vm.registerNative`, args shape, return value, the seven built-ins            |
| [Reference parameters](docs/references.md)    | `Ref<T>` for scalar / struct / struct-array refs on publics and natives       |
| [Serialize & structs](docs/serialize.md)      | `serializeStruct`, `getJsonTag`, host JSON round-trip, struct shapes          |
| [Error handling](docs/errors.md)              | `PmxScriptException`, decoder errors, decryption errors, instruction limits   |
| [JSON helper](docs/json.md)                   | Stand-alone `com.lenerotex.pmx.json.Json` parser + encoder                    |

## Layout

```
pmx-kt/
  build.gradle.kts
  settings.gradle.kts
  gradlew, gradlew.bat, gradle/wrapper/
  src/
    main/kotlin/com/lenerotex/pmx/
      Errors.kt   Types.kt
      Decoder.kt  Loader.kt
      Vm.kt       Script.kt    Natives.kt
      json/Json.kt
    test/kotlin/com/lenerotex/pmx/...
  docs/
```

Crypto (`deriveKey`, `aesGcmDecrypt`) and opcode / tag tables live in
[`pmx-format-kt`](../pmx-format-kt); pmx-kt depends on it via the `api`
configuration, so consumers get both on the classpath transparently.

## Status

Synchronous v1 of the language: loader, decoder, every opcode in
[`Op`](../runtime/src/opcodes.ts), all four serialize/deserialize variants,
scalar / struct / struct-array native refs, public-side scalar refs,
`Script.getJsonTag(...)`, seven built-in natives.

Out of scope (use the TS runtime if you need them):

- async natives (no coroutines layer)
- the VS Code editor / the `pmx` CLI

## License

Apache-2.0.
