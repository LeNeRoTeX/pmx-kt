package com.lenerotex.pmx.json

/**
 * Pluggable JSON codec used by the VM's `SERIALIZE` / `DESERIALIZE` opcodes
 * and by [com.lenerotex.pmx.Script.serializeStruct].
 *
 * The default implementation, [DefaultJsonCodec], delegates to the bundled
 * zero-dependency [Json] object. Hosts can install Jackson, kotlinx.serialization,
 * Moshi, etc. by providing their own [JsonCodec] on
 * [com.lenerotex.pmx.VMOptions].
 *
 * ### Contract
 *
 * - [encode] receives a tree of `Map<String, Any?>` / `List<Any?>` / scalars
 *   (`Long`, `Double`, `Boolean`, `String`, `null`) — the same shape [Json.encode]
 *   accepts. It must return a self-contained JSON string.
 * - [decode] receives a JSON string and must return the same shape: a
 *   `Map<String, Any?>` for objects, a `List<Any?>` for arrays, or a scalar
 *   (`Long` / `Double` / `Boolean` / `String` / `null`) for top-level scalars.
 *
 * Implementations may throw any exception type from either method; the VM
 * normalizes decoder errors into `IllegalStateException("pmx: deserialize: invalid JSON payload (...)")`.
 */
interface JsonCodec {
    /** Encode a Map / List / scalar tree (the shape [Json.decode] returns) to a JSON string. */
    fun encode(value: Any?): String

    /**
     * Decode a JSON string. Must return `Map<String, Any?>`, `List<Any?>`, or a
     * scalar (`Long` / `Double` / `Boolean` / `String` / `null`).
     */
    fun decode(text: String): Any?
}

/** Default codec, delegates to the bundled zero-dependency [Json]. */
object DefaultJsonCodec : JsonCodec {
    override fun encode(value: Any?): String = Json.encode(value)
    override fun decode(text: String): Any? = Json.decode(text)
}
