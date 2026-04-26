package com.lenerotex.pmx

import com.lenerotex.pmx.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JsonTest {
    @Test fun `decodes primitives`() {
        assertEquals(42L, Json.decode("42"))
        assertEquals(-5L, Json.decode("-5"))
        assertEquals(3.14, Json.decode("3.14"))
        assertEquals(true, Json.decode("true"))
        assertEquals(false, Json.decode("false"))
        assertEquals(null, Json.decode("null"))
        assertEquals("hi\nthere", Json.decode("\"hi\\nthere\""))
    }

    @Test fun `decodes nested object`() {
        val v = Json.decode("""{"a":1,"b":[true,"x",null],"c":{"d":3.5}}""")
        @Suppress("UNCHECKED_CAST") val m = v as Map<String, Any?>
        assertEquals(1L, m["a"])
        assertEquals(listOf(true, "x", null), m["b"])
        assertEquals(mapOf("d" to 3.5), m["c"])
    }

    @Test fun `encodes maps and lists deterministically`() {
        val map = linkedMapOf<String, Any?>("a" to 1L, "b" to listOf(2L, "x"), "c" to true, "d" to null)
        assertEquals("""{"a":1,"b":[2,"x"],"c":true,"d":null}""", Json.encode(map))
    }

    @Test fun `escapes special chars`() {
        assertEquals(""""he said \"hi\""""", Json.encode("""he said "hi""""))
        assertEquals(""""a\nb\tc"""", Json.encode("a\nb\tc"))
    }

    @Test fun `rejects garbage`() {
        assertFailsWith<IllegalArgumentException> { Json.decode("{") }
        assertFailsWith<IllegalArgumentException> { Json.decode("[1,") }
    }
}
