package com.lenerotex.pmx

import com.lenerotex.pmx.json.DefaultJsonCodec
import com.lenerotex.pmx.json.JsonCodec

/** A native function: receives positional args (values or [Ref] boxes), returns a cell value. */
typealias NativeFn = (args: List<Any?>) -> Any?

class VMOptions(
    /** Maximum number of opcodes per public/main invocation. */
    var instructionLimit: Long = 1_000_000L,
    /**
     * Codec used by the `SERIALIZE` / `DESERIALIZE` opcodes. Defaults to the bundled
     * zero-dependency [DefaultJsonCodec]; hosts may swap in Jackson, kotlinx.serialization,
     * etc. either at construction or at runtime via `vm.options.jsonCodec = ...`.
     */
    var jsonCodec: JsonCodec = DefaultJsonCodec,
)

/**
 * VM is the host-side runtime. Native functions are registered on the VM,
 * then a loaded [Script] can be executed using its public-table entries.
 */
class VM(val options: VMOptions = VMOptions()) {
    private val natives = HashMap<String, NativeFn>()

    fun registerNative(name: String, fn: NativeFn) {
        natives[name] = fn
    }

    fun has(name: String): Boolean = name in natives
    fun resolve(name: String): NativeFn? = natives[name]
}
