package com.lenerotex.pmx

import com.lenerotex.pmx.format.ConstTag
import com.lenerotex.pmx.format.NO_CONST
import com.lenerotex.pmx.format.NO_STRUCT
import com.lenerotex.pmx.format.Op
import com.lenerotex.pmx.format.PMX_VERSION
import com.lenerotex.pmx.format.TypeTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DecoderTest {
    @Test fun `parseHeader rejects bad magic`() {
        val bad = ByteArray(24) { 0x00 }
        bad[0] = 'X'.code.toByte()
        assertFailsWith<IllegalArgumentException> { parseHeader(bad) }
    }

    @Test fun `parseHeader returns version flags iv payloadLen`() {
        val mb = ModuleBuilder().apply { str("hello") }
        val payload = mb.encodePayload()
        val file = wrapHeader(payload)
        val hdr = parseHeader(file)
        assertEquals(PMX_VERSION, hdr.version)
        assertEquals(0, hdr.flags)
        assertEquals(false, hdr.encrypted)
        assertEquals(payload.size, hdr.payloadLen)
        assertEquals(12, hdr.iv.size)
    }

    @Test fun `decodePayload round-trips strings consts globals structs natives publics funcs code`() {
        val mb = ModuleBuilder()
        val sFoo = mb.str("foo")
        val sBar = mb.str("bar")
        val cInt = mb.intConst(42L)
        val cStr = mb.strConst("hello")
        mb.globals.add(GlobalInfo(size = 1, initConst = cInt))
        mb.globals.add(GlobalInfo(size = 3, initConst = NO_CONST))

        val pInfoName = mb.str("pInfo")
        val pK = mb.str("pKills")
        val pTag = mb.str("kills")
        mb.structs.add(
            StructInfo(
                nameStr = pInfoName,
                entries = listOf(
                    StructEntryInfo(nameStr = pK, tagStr = pTag, type = TypeTag.CELL),
                ),
            ),
        )

        mb.natives.add(
            NativeInfo(
                nameStr = sFoo,
                arity = 1,
                params = listOf(
                    ParamInfo(type = TypeTag.CELL, isRef = false, isStructArray = false, structIdx = NO_STRUCT),
                ),
            ),
        )

        mb.publics.add(
            PublicInfo(
                nameStr = sBar,
                codeOffset = 0,
                arity = 0,
                numLocals = 0,
                params = emptyList(),
            ),
        )
        mb.funcs.add(
            FuncInfo(
                codeOffset = 7, arity = 1, numLocals = 1,
                params = listOf(ParamInfo(TypeTag.CELL, false, false, NO_STRUCT)),
            ),
        )

        mb.code.pushInt(1).halt()

        val payload = mb.encodePayload()
        val mod = decodePayload(payload)

        assertEquals(listOf("foo", "bar", "hello", "pInfo", "pKills", "kills"), mod.strings)
        assertEquals(2, mod.consts.size)
        assertEquals(42L, mod.consts[0].intVal)
        assertEquals(ConstTag.STR_REF, mod.consts[1].tag)

        assertEquals(2, mod.globals.size)
        assertEquals(1, mod.globals[0].size)
        assertEquals(cInt, mod.globals[0].initConst)
        assertEquals(NO_CONST, mod.globals[1].initConst)

        assertEquals(1, mod.structs.size)
        assertEquals("pInfo", mod.strings[mod.structs[0].nameStr])
        assertEquals("kills", mod.strings[mod.structs[0].entries[0].tagStr])

        assertEquals(1, mod.natives.size)
        assertEquals(1, mod.publics.size)
        assertEquals(1, mod.funcs.size)

        assertTrue(mod.code.size >= 6)
        assertEquals(Op.PUSH_INT.toByte(), mod.code[0])
        assertEquals(Op.HALT.toByte(), mod.code[5])
    }

    @Test fun `loader rejects wrong version`() {
        val mb = ModuleBuilder().apply { str("x") }
        val payload = mb.encodePayload()
        val file = wrapHeader(payload, version = 99)
        val ex = assertFailsWith<IllegalStateException> { loadPmxBytes(file, VM()) }
        assertTrue("unsupported bytecode version" in (ex.message ?: ""))
    }

    @Test fun `loader accepts plaintext file`() {
        val mb = ModuleBuilder()
        val sMain = mb.str("main")
        mb.publics.add(PublicInfo(nameStr = sMain, codeOffset = 0, arity = 0, numLocals = 0, params = emptyList()))
        mb.code.pushInt(7).halt()
        val payload = mb.encodePayload()
        val file = wrapHeader(payload)
        val script = loadPmxBytes(file, VM())
        assertEquals(7L, script.start())
    }
}
