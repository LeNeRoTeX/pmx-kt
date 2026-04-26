package com.lenerotex.pmx

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Derive an AES-256 key from a passphrase via SHA-256, mirroring `pxc --key`. */
internal fun deriveKey(passphrase: String): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    return md.digest(passphrase.toByteArray(Charsets.UTF_8))
}

/**
 * Decrypt an AES-256-GCM payload. [ciphertext] must be the ciphertext concatenated
 * with the 16-byte auth tag (the standard GCM output the Go compiler produces).
 */
internal fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
    require(key.size == 32) { "pmx: key must be 32 bytes (was ${key.size})" }
    require(iv.size == 12) { "pmx: iv must be 12 bytes (was ${iv.size})" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val spec = GCMParameterSpec(128, iv)
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
    return try {
        cipher.doFinal(ciphertext)
    } catch (e: Exception) {
        throw IllegalStateException(
            "pmx: decryption failed (wrong key or tampered file): ${e.message}",
            e,
        )
    }
}
