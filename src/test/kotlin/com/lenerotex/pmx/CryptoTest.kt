package com.lenerotex.pmx

import com.lenerotex.pmx.format.PMX_VERSION
import com.lenerotex.pmx.format.aesGcmDecrypt
import com.lenerotex.pmx.format.deriveKey
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CryptoTest {
    @Test fun `roundtrip aes-256-gcm with passphrase-derived key`() {
        val key = deriveKey("testkey")
        val iv = ByteArray(12) { (it + 1).toByte() }
        val plaintext = "the quick brown fox".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plaintext)

        val pt = aesGcmDecrypt(key, iv, ct)
        assertEquals(plaintext.toList(), pt.toList())
    }

    @Test fun `decrypt rejects wrong key`() {
        val key = deriveKey("right")
        val iv = ByteArray(12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(byteArrayOf(1, 2, 3))

        val wrong = deriveKey("wrong")
        assertFailsWith<IllegalStateException> { aesGcmDecrypt(wrong, iv, ct) }
    }

    @Test fun `loader rejects missing key for encrypted file`() {
        // Encrypt a tiny payload with a known key, then try to load with no key.
        val mb = ModuleBuilder()
        mb.publics.add(PublicInfo(mb.str("main"), 0, 0, 0, emptyList()))
        mb.code.pushInt(1).ret()
        val payload = mb.encodePayload()

        val key = deriveKey("k")
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(payload)

        val out = java.io.ByteArrayOutputStream()
        out.write("PMX1".toByteArray(Charsets.US_ASCII))
        val w = LeWriter(out)
        w.u16(PMX_VERSION); w.u16(1) // encrypted flag
        w.bytes(iv); w.u32(ct.size); w.bytes(ct)
        val file = out.toByteArray()

        assertFailsWith<IllegalArgumentException> { loadPmxBytes(file, VM()) }
        // With the right key it loads.
        val s = loadPmxBytes(file, VM(), key = "k")
        assertEquals(1L, s.start())
    }
}
