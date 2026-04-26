package com.lenerotex.pmx

import com.lenerotex.pmx.json.DefaultJsonCodec
import com.lenerotex.pmx.json.Json
import com.lenerotex.pmx.json.JsonCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonCodecTest {

    private fun buildStructModule(): ModuleBuilder {
        val mb = ModuleBuilder()
        val pInfoName = mb.str("pInfo")
        val pK = mb.str("pKills"); val pKTag = mb.str("kills")
        val pD = mb.str("pDeaths"); val pDTag = mb.str("deaths")
        mb.structs.add(
            StructInfo(
                nameStr = pInfoName,
                entries = listOf(
                    StructEntryInfo(pK, pKTag, TypeTag.CELL),
                    StructEntryInfo(pD, pDTag, TypeTag.CELL),
                ),
            ),
        )
        mb.globals.add(GlobalInfo(size = 2, initConst = NO_CONST))
        return mb
    }

    private fun emitSerialize(mb: ModuleBuilder) {
        val mainStr = mb.str("main")
        val c = mb.code
        c.globalAddr(0).pushInt(7).op(Op.STORE_HEAP)
        c.globalAddr(0).pushInt(1).op(Op.ADD).pushInt(3).op(Op.STORE_HEAP)
        c.globalAddr(0).op(Op.SERIALIZE).u32(0)
        c.ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
    }

    private fun emitDeserialize(mb: ModuleBuilder, payload: String) {
        val mainStr = mb.str("main")
        val payloadConst = mb.strConst(payload)
        val c = mb.code
        c.globalAddr(0).pushConst(payloadConst).pushInt(0)
        c.op(Op.DESERIALIZE).u32(0)
        c.op(Op.POP)
        c.globalAddr(0).op(Op.SERIALIZE).u32(0)
        c.ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
    }

    // ------------------------------------------------------------
    // Custom encoder is invoked by SERIALIZE
    // ------------------------------------------------------------

    @Test fun `custom codec encodes serialize output`() {
        var seen: Any? = null
        val codec = object : JsonCodec {
            override fun encode(value: Any?): String {
                seen = value
                return "#CUSTOM#" + Json.encode(value)
            }
            override fun decode(text: String): Any? = Json.decode(text)
        }
        val vm = VM(VMOptions(jsonCodec = codec))
        val mb = buildStructModule()
        emitSerialize(mb)

        val out = Script(mb.build(), vm).start() as String

        assertTrue(out.startsWith("#CUSTOM#"), "expected codec prefix in $out")
        assertEquals("""#CUSTOM#{"kills":7,"deaths":3}""", out)
        @Suppress("UNCHECKED_CAST")
        val map = seen as Map<String, Any?>
        assertEquals(7L, map["kills"])
        assertEquals(3L, map["deaths"])
    }

    // ------------------------------------------------------------
    // Custom decoder is invoked by DESERIALIZE
    // ------------------------------------------------------------

    @Test fun `custom codec decodes deserialize payload`() {
        val codec = object : JsonCodec {
            override fun encode(value: Any?): String = DefaultJsonCodec.encode(value)
            override fun decode(text: String): Any? {
                val stripped = if (text.startsWith("#CUSTOM#")) text.removePrefix("#CUSTOM#") else text
                return DefaultJsonCodec.decode(stripped)
            }
        }
        val vm = VM(VMOptions(jsonCodec = codec))
        val mb = buildStructModule()
        emitDeserialize(mb, """#CUSTOM#{"kills":11,"deaths":4}""")

        val ret = Script(mb.build(), vm).start() as String

        @Suppress("UNCHECKED_CAST")
        val obj = Json.decode(ret) as Map<String, Any?>
        assertEquals(11L, obj["kills"])
        assertEquals(4L, obj["deaths"])
    }

    // ------------------------------------------------------------
    // Decoder errors keep the existing IllegalStateException shape
    // ------------------------------------------------------------

    @Test fun `codec decode failure surfaces as invalid JSON payload error`() {
        val codec = object : JsonCodec {
            override fun encode(value: Any?): String = DefaultJsonCodec.encode(value)
            override fun decode(text: String): Any? = throw RuntimeException("nope")
        }
        val vm = VM(VMOptions(jsonCodec = codec))
        val mb = buildStructModule()
        emitDeserialize(mb, """{"kills":1,"deaths":2}""")

        val ex = assertFailsWith<IllegalStateException> { Script(mb.build(), vm).start() }
        assertEquals("pmx: deserialize: invalid JSON payload (nope)", ex.message)
        assertEquals("nope", ex.cause?.message)
    }

    // ------------------------------------------------------------
    // Default behaviour is unchanged when no codec is provided
    // ------------------------------------------------------------

    @Test fun `default codec round-trips through bundled Json`() {
        val mb = buildStructModule()
        emitSerialize(mb)

        val out = Script(mb.build(), VM()).start() as String

        assertEquals("""{"kills":7,"deaths":3}""", out)
    }

    @Test fun `default codec is DefaultJsonCodec`() {
        val vm = VM()
        assertEquals(DefaultJsonCodec, vm.options.jsonCodec)
    }

    @Test fun `runtime swap of jsonCodec is honored`() {
        val vm = VM().also { registerBuiltins(it) }
        val mb = buildStructModule()
        emitSerialize(mb)
        val script = Script(mb.build(), vm)

        assertEquals("""{"kills":7,"deaths":3}""", script.start() as String)

        vm.options.jsonCodec = object : JsonCodec {
            override fun encode(value: Any?): String = "swapped"
            override fun decode(text: String): Any? = DefaultJsonCodec.decode(text)
        }
        assertEquals("swapped", script.start() as String)
    }
}
