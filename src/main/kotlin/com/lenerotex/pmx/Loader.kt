package com.lenerotex.pmx

import com.lenerotex.pmx.format.PMX_VERSION
import com.lenerotex.pmx.format.aesGcmDecrypt
import com.lenerotex.pmx.format.deriveKey
import java.io.File

/** Load and decrypt a `.pmx` file from disk. */
fun loadPmx(
    path: File,
    vm: VM,
    key: String? = null,
    stubMissingNatives: Boolean = false,
): Script = loadPmxBytes(path.readBytes(), vm, key, stubMissingNatives)

/** Load a `.pmx` file already in memory. */
fun loadPmxBytes(
    bytes: ByteArray,
    vm: VM,
    key: String? = null,
    stubMissingNatives: Boolean = false,
): Script {
    val hdr = parseHeader(bytes)
    if (hdr.version != PMX_VERSION) {
        throw IllegalStateException(
            "pmx: unsupported bytecode version ${hdr.version} (expected $PMX_VERSION); recompile with the latest pxc",
        )
    }
    val end = 24 + hdr.payloadLen
    require(end <= bytes.size) { "pmx: declared payloadLen ${hdr.payloadLen} exceeds file size ${bytes.size - 24}" }
    val blob = bytes.copyOfRange(24, end)
    val payload = if (hdr.encrypted) {
        val k = key ?: throw IllegalArgumentException("pmx: file is encrypted; pass `key` to loadPmx")
        aesGcmDecrypt(deriveKey(k), hdr.iv, blob)
    } else {
        blob
    }
    val module = decodePayload(payload)
    return Script(module, vm, stubMissingNatives = stubMissingNatives)
}
