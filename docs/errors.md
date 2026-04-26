# Error handling

`pmx-kt` uses two error tiers:

1. **`PmxScriptException`** — script-initiated aborts (the `exception(code,
   msg)` built-in). The host can pattern-match `code` and recover.
2. **`IllegalStateException` / `IllegalArgumentException`** — every
   other failure (decoder, decryption, type mismatch, instruction
   limit, unresolved native, etc.). These are bugs in either the
   bytecode or the host integration and usually shouldn't be caught
   except at the top level.

## `PmxScriptException`

```kotlin
package com.lenerotex.pmx

class PmxScriptException(val code: Int, message: String) : RuntimeException(message)
```

Thrown when the script-side `exception(code, msg)` native fires:

```pawn
public OnReceive(payload) {
    if (strlen(payload) > 1024) {
        exception(413, "payload too large");
    }
    return 1;
}
```

```kotlin
try {
    script.callPublic("OnReceive", listOf(payload))
} catch (e: PmxScriptException) {
    // mirror Hono / HTTP responses, structured logging, etc.
    println("script aborted: code=${e.code} message=${e.message}")
}
```

The host may also raise a `PmxScriptException` directly from a native:

```kotlin
vm.registerNative("DbFetch") { args ->
    val id = args[0] as String
    db.find(id) ?: throw PmxScriptException(404, "no row $id")
}
```

## Decoder failures

Bad magic, truncated headers, wrong version, malformed sections —
all surface as `IllegalArgumentException` (header / sanity) or
`IllegalStateException` (post-load checks). Examples:

```
java.lang.IllegalArgumentException: pmx: bad magic "XMP1"
java.lang.IllegalStateException: pmx: unsupported bytecode version 5 (expected 6); recompile with the latest pxc
```

If you build a release pipeline, validate the version before deploying
new scripts:

```kotlin
import com.lenerotex.pmx.PMX_VERSION
import com.lenerotex.pmx.parseHeader

val hdr = parseHeader(file.readBytes())
require(hdr.version == PMX_VERSION) {
    "stale .pmx (v${hdr.version}); rebuild with the latest pxc"
}
```

## Decryption failures

Wrong key or tampered ciphertext both fail the AES-GCM auth tag check,
which surfaces as:

```
java.lang.IllegalStateException: pmx: decryption failed (wrong key or tampered file): ...
```

This is intentional — the on-disk format does not distinguish between
the two. Keep your `.pmx` files, the encryption key, and a known-good
test script under integrity-check, and re-run the loader at startup so
a corrupted artifact crashes early rather than mid-request.

```kotlin
val script = try {
    loadPmx(File("game.pmx"), vm, key = pmxKey)
} catch (e: IllegalStateException) {
    log.error("pmx load failed: ${e.message}")
    throw e
}
```

## Unresolved natives

By default `Script` construction fails fast if the bytecode references a
native the VM hasn't registered:

```
java.lang.IllegalStateException: pmx: unresolved native "MyMissingFn"
```

Pass `stubMissingNatives = true` to substitute a no-op (returns `0L`)
and continue, with a warning written to stderr:

```kotlin
val script = loadPmx(File("script.pmx"), vm, stubMissingNatives = true)
```

## Instruction limit

Every `callPublic` / `start()` invocation is capped by the VM's
`instructionLimit` (default `1_000_000`). When tripped:

```
java.lang.IllegalStateException: pmx: instruction limit 1000000 exceeded
```

Lift it for trusted bytecode, lower it for untrusted user scripts:

```kotlin
val sandbox  = VM(VMOptions(instructionLimit = 50_000L))
val internal = VM(VMOptions(instructionLimit = 200_000_000L))
```

## Argument shape mismatches

`callPublic` validates arity and ref/value shape before running:

```
java.lang.IllegalArgumentException: pmx: OnReceive expects 2 args, got 1
java.lang.IllegalArgumentException: pmx: callPublic: OnReceive arg 2 must be a Ref { value } box (declared with &)
java.lang.IllegalArgumentException: pmx: callPublic: Bump arg 1 is a value parameter; pass the value directly, not a Ref box
```

These are programmer errors, not user-data errors — fix the call site.

## Arithmetic errors

Integer division and modulo by zero throw `ArithmeticException`:

```
java.lang.ArithmeticException: pmx: integer division by zero
java.lang.ArithmeticException: pmx: modulo by zero
```

Float division by zero follows the IEEE-754 rules and returns `Inf` /
`NaN` rather than throwing.

## A robust top-level handler

```kotlin
fun runSafely(script: Script, name: String, args: List<Any?> = emptyList()): Result<Any> = try {
    Result.success(script.callPublic(name, args))
} catch (e: PmxScriptException) {
    log.warn("script aborted: code=${e.code}", e)
    Result.failure(e)
} catch (e: ArithmeticException) {
    log.warn("script arithmetic error", e)
    Result.failure(e)
} catch (e: IllegalArgumentException) {
    log.error("bad call shape", e)
    Result.failure(e)
} catch (e: IllegalStateException) {
    log.error("VM/runtime failure", e)
    Result.failure(e)
}
```
