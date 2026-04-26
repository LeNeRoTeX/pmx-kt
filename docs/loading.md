# Loading scripts

`pmx-kt` exposes two entry points:

```kotlin
fun loadPmx(path: java.io.File, vm: VM, key: String? = null,
            stubMissingNatives: Boolean = false): Script

fun loadPmxBytes(bytes: ByteArray, vm: VM, key: String? = null,
                 stubMissingNatives: Boolean = false): Script
```

Both build a [`Script`](../src/main/kotlin/com/lenerotex/pmx/Script.kt)
bound to a [`VM`](../src/main/kotlin/com/lenerotex/pmx/Vm.kt).

## Plaintext file

If the bytecode was compiled without `--key`, no key is needed:

```kotlin
import com.lenerotex.pmx.*
import java.io.File

val vm = VM().also { registerBuiltins(it) }
val script = loadPmx(File("hello.pmx"), vm)
println(script.start())   // runs main()
```

## Encrypted file (AES-256-GCM)

The Go compiler (`pxc build --key=<passphrase>`) writes an AES-GCM
ciphertext after a 24-byte clear header. The key is derived as
`SHA-256(passphrase)`.

```kotlin
val script = loadPmx(
    File("gamemode.pmx"),
    vm,
    key = System.getenv("PMX_KEY"),
)
```

Wrong key, missing key, or a tampered file will throw — see
[Error handling](errors.md).

## Loading from memory

Useful for serverless / fat-jar deployments where the bytecode lives in
classpath resources, S3, a database row, etc.

```kotlin
val bytes: ByteArray = MyResource.openBytecode().use { it.readBytes() }
val script = loadPmxBytes(bytes, vm, key = config.pmxKey)
```

## Stubbing missing natives

By default, loading fails fast if the bytecode references a native that
the host hasn't registered:

```
java.lang.IllegalStateException: pmx: unresolved native "MyMissingFn"
```

Pass `stubMissingNatives = true` to substitute a no-op (returns `0L`)
that emits a warning to stderr. Handy for partial integrations and
diagnostic tooling:

```kotlin
val script = loadPmx(File("script.pmx"), vm, stubMissingNatives = true)
```

## Re-loading without restarting the VM

A `VM` holds the native registry; a `Script` holds the heap and module.
You can load multiple scripts against the same `VM` and the natives are
shared:

```kotlin
val vm = VM().also { registerBuiltins(it) }
vm.registerNative("DbFetch") { args -> /* ... */ }

val a = loadPmx(File("a.pmx"), vm, key = pmxKey)
val b = loadPmx(File("b.pmx"), vm, key = pmxKey)

a.start()
b.start()
```

## Custom VM options

`VM` takes an optional [`VMOptions`](../src/main/kotlin/com/lenerotex/pmx/Vm.kt)
record. Currently it exposes `instructionLimit`, the per-call cap on
opcodes (default `1_000_000`):

```kotlin
val vm = VM(VMOptions(instructionLimit = 200_000_000L))
```

When the limit is exceeded the VM throws
`IllegalStateException("pmx: instruction limit ... exceeded")`. See
[Error handling](errors.md) for full coverage of runtime errors.

## Bytecode header

For tooling that wants to inspect the file without loading it:

```kotlin
import com.lenerotex.pmx.parseHeader
import com.lenerotex.pmx.PMX_VERSION

val hdr = parseHeader(file.readBytes())
println("PMX v${hdr.version}, encrypted=${hdr.encrypted}, payload=${hdr.payloadLen} bytes")
require(hdr.version == PMX_VERSION) { "stale .pmx, rebuild with the latest pxc" }
```
