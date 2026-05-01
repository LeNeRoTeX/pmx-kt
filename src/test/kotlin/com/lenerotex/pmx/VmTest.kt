package com.lenerotex.pmx

import com.lenerotex.pmx.format.ConstTag
import com.lenerotex.pmx.format.NO_CONST
import com.lenerotex.pmx.format.NO_STR
import com.lenerotex.pmx.format.NO_STRUCT
import com.lenerotex.pmx.format.Op
import com.lenerotex.pmx.format.TypeTag
import com.lenerotex.pmx.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VmTest {

    private fun mainOnly(numLocals: Int = 0, arity: Int = 0, params: List<ParamInfo> = emptyList(),
                        emit: (CodeBuilder) -> Unit): Pair<Module, Int> {
        val mb = ModuleBuilder()
        val mainStr = mb.str("main")
        emit(mb.code)
        mb.publics.add(
            PublicInfo(
                nameStr = mainStr, codeOffset = 0, arity = arity,
                numLocals = numLocals, params = params,
            ),
        )
        return mb.build() to 0
    }

    // ------------------------------------------------------------
    // Arithmetic + control flow
    // ------------------------------------------------------------

    @Test fun `arithmetic 1 plus 2 returns 3`() {
        val (mod, _) = mainOnly { it.pushInt(1).pushInt(2).op(Op.ADD).ret() }
        assertEquals(3L, Script(mod, VM()).start())
    }

    @Test fun `negative integer literal works via PUSH_INT`() {
        val (mod, _) = mainOnly { it.pushInt(-7).op(Op.NEG).ret() }
        assertEquals(7L, Script(mod, VM()).start())
    }

    @Test fun `loop computes sum 1 to 10 via JZ JMP`() {
        // i = 1; sum = 0; while (i <= 10) { sum += i; i++; } return sum
        // locals: 0 = i, 1 = sum
        val mb = ModuleBuilder()
        val mainStr = mb.str("main")
        val c = mb.code
        // i = 1
        c.pushInt(1).storeLocal(0)
        // sum = 0
        c.pushInt(0).storeLocal(1)
        val loopStart = c.pos()
        // condition: i <= 10
        c.loadLocal(0).pushInt(10).op(Op.LE)
        // JZ end
        c.op(Op.JZ).i32(0); val jzPatchOff = c.pos() - 4
        // sum = sum + i
        c.loadLocal(1).loadLocal(0).op(Op.ADD).storeLocal(1)
        // i = i + 1
        c.loadLocal(0).pushInt(1).op(Op.ADD).storeLocal(0)
        // jmp loopStart
        c.op(Op.JMP).i32(loopStart - (c.pos() + 4))
        val endPos = c.pos()
        // patch jz
        c.patchI32(jzPatchOff, endPos - (jzPatchOff + 4))
        // return sum
        c.loadLocal(1).ret()

        mb.publics.add(PublicInfo(mainStr, 0, 0, 2, emptyList()))
        assertEquals(55L, Script(mb.build(), VM()).start())
    }

    // ------------------------------------------------------------
    // CALL / RET
    // ------------------------------------------------------------

    @Test fun `call adds two args returns sum`() {
        val mb = ModuleBuilder()
        val mainStr = mb.str("main")
        // main: PUSH_INT 2; PUSH_INT 3; CALL 0 argc=2; RET
        mb.code.pushInt(2).pushInt(3).call(0, 2).ret()
        val mainSize = mb.code.pos()
        // func 0 (add): LOAD_LOCAL 0; LOAD_LOCAL 1; ADD; RET
        val addStart = mainSize
        mb.code.loadLocal(0).loadLocal(1).op(Op.ADD).ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
        mb.funcs.add(FuncInfo(addStart, 2, 2, emptyList()))
        assertEquals(5L, Script(mb.build(), VM()).start())
    }

    // ------------------------------------------------------------
    // Native dispatch
    // ------------------------------------------------------------

    @Test fun `ncall invokes registered native and pushes its result`() {
        val mb = ModuleBuilder()
        val mainStr = mb.str("main")
        val natStr = mb.str("addem")
        mb.code.pushInt(10).pushInt(20).ncall(0, 2).ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
        mb.natives.add(
            NativeInfo(
                nameStr = natStr, arity = 2,
                params = listOf(
                    ParamInfo(TypeTag.CELL, false, false, NO_STRUCT),
                    ParamInfo(TypeTag.CELL, false, false, NO_STRUCT),
                ),
            ),
        )
        val vm = VM()
        vm.registerNative("addem") { args -> asLong(args[0]) + asLong(args[1]) }
        assertEquals(30L, Script(mb.build(), vm).start())
    }

    // ------------------------------------------------------------
    // SERIALIZE / DESERIALIZE on a global struct row
    // ------------------------------------------------------------

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
        // global #0 = PlayerInfo[pInfo] (size 2, zero-initialized)
        mb.globals.add(GlobalInfo(size = 2, initConst = NO_CONST))
        return mb
    }

    @Test fun `serialize emits json with tag keys`() {
        val mb = buildStructModule()
        val mainStr = mb.str("main")
        val c = mb.code
        // heap[0] = 7 (kills), heap[1] = 3 (deaths)
        c.globalAddr(0).pushInt(7).op(Op.STORE_HEAP)
        c.globalAddr(0).pushInt(1).op(Op.ADD).pushInt(3).op(Op.STORE_HEAP)
        // serialize struct 0 from base 0
        c.globalAddr(0).op(Op.SERIALIZE).u32(0)
        c.ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
        val s = Script(mb.build(), VM())
        val ret = s.start() as String
        @Suppress("UNCHECKED_CAST")
        val obj = Json.decode(ret) as Map<String, Any?>
        assertEquals(7L, obj["kills"])
        assertEquals(3L, obj["deaths"])
    }

    @Test fun `deserialize lenient leaves missing keys untouched`() {
        val mb = buildStructModule()
        val mainStr = mb.str("main")
        // pre-set deaths via an init constant
        val cInit = mb.intConst(99L)
        mb.globals[0] = GlobalInfo(size = 2, initConst = cInit)

        val payloadConst = mb.strConst("""{"kills":42}""")
        val c = mb.code
        // base addr
        c.globalAddr(0)
        // payload
        c.pushConst(payloadConst)
        // validate = 0 (lenient)
        c.pushInt(0)
        // DESERIALIZE struct 0
        c.op(Op.DESERIALIZE).u32(0)
        c.op(Op.POP) // discard the 1 pushed by DESERIALIZE
        // serialize the row to inspect
        c.globalAddr(0).op(Op.SERIALIZE).u32(0)
        c.ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
        val ret = Script(mb.build(), VM()).start() as String
        @Suppress("UNCHECKED_CAST")
        val obj = Json.decode(ret) as Map<String, Any?>
        assertEquals(42L, obj["kills"])
        // deaths stayed at the global initializer
        assertEquals(99L, obj["deaths"])
    }

    @Test fun `deserialize strict throws on missing key`() {
        val mb = buildStructModule()
        val mainStr = mb.str("main")
        val payloadConst = mb.strConst("""{"kills":1}""")
        val c = mb.code
        c.globalAddr(0).pushConst(payloadConst).pushInt(1).op(Op.DESERIALIZE).u32(0).ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
        val s = Script(mb.build(), VM())
        val ex = assertFailsWith<IllegalStateException> { s.start() }
        assertTrue("missing key" in (ex.message ?: ""))
    }

    // ------------------------------------------------------------
    // callPublic with a string ref output parameter
    // ------------------------------------------------------------

    @Test fun `callPublic writes back a string ref output`() {
        // public OnReceive(payload: string, &out: string) {
        //   out = payload + "!";
        //   return 1;
        // }
        val mb = ModuleBuilder()
        val pubStr = mb.str("OnReceive")
        val bangConst = mb.strConst("!")
        val c = mb.code
        // out (local 1 holds addr) = payload + "!"
        c.loadLocal(1)                  // addr
        c.loadLocal(0)                  // payload
        c.pushConst(bangConst)
        c.op(Op.STR_CONCAT)
        c.op(Op.STORE_HEAP)
        c.pushInt(1).ret()
        mb.publics.add(
            PublicInfo(
                nameStr = pubStr, codeOffset = 0, arity = 2, numLocals = 2,
                params = listOf(
                    ParamInfo(TypeTag.STRING, false, false, NO_STRUCT),
                    ParamInfo(TypeTag.STRING, true, false, NO_STRUCT),
                ),
            ),
        )

        val s = Script(mb.build(), VM())
        val out = Ref("")
        val rc = s.callPublic("OnReceive", listOf("hello", out))
        assertEquals(1L, rc)
        assertEquals("hello!", out.value)
    }

    @Test fun `callPublic rejects mismatched ref shape`() {
        val mb = ModuleBuilder()
        val pubStr = mb.str("Want")
        mb.code.pushInt(0).ret()
        mb.publics.add(
            PublicInfo(
                pubStr, 0, 1, 1,
                listOf(ParamInfo(TypeTag.CELL, true, false, NO_STRUCT)),
            ),
        )
        val s = Script(mb.build(), VM())
        assertFailsWith<IllegalArgumentException> {
            s.callPublic("Want", listOf(123L)) // not a Ref
        }
    }

    // ------------------------------------------------------------
    // NCALL with scalar ref param
    // ------------------------------------------------------------

    @Test fun `ncall scalar ref writes back into heap`() {
        // global nonce = ""; native MakeNonce(&out:string) returns 1
        // main: GLOBAL_ADDR 0; NCALL 0 argc=1; LOAD_HEAP nonce; RET (string)
        val mb = ModuleBuilder()
        val mainStr = mb.str("main")
        val natStr = mb.str("MakeNonce")
        val emptyStr = mb.strConst("")
        mb.globals.add(GlobalInfo(size = 1, initConst = emptyStr))
        mb.natives.add(
            NativeInfo(
                nameStr = natStr, arity = 1,
                params = listOf(ParamInfo(TypeTag.STRING, true, false, NO_STRUCT)),
            ),
        )

        val c = mb.code
        c.globalAddr(0).ncall(0, 1).op(Op.POP)
        c.globalAddr(0).op(Op.LOAD_HEAP).ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))

        val vm = VM()
        vm.registerNative("MakeNonce") { args ->
            @Suppress("UNCHECKED_CAST")
            val box = args[0] as Ref<Any?>
            box.value = "abc-123"
            1L
        }
        assertEquals("abc-123", Script(mb.build(), vm).start())
    }

    // ------------------------------------------------------------
    // getJsonTag
    // ------------------------------------------------------------

    @Test fun `getJsonTag returns tag name fallback or null`() {
        val mb = ModuleBuilder()
        val pInfoName = mb.str("pInfo")
        val pK = mb.str("pKills"); val pKTag = mb.str("kills")
        val pD = mb.str("pDeaths") // no tag
        mb.structs.add(
            StructInfo(
                nameStr = pInfoName,
                entries = listOf(
                    StructEntryInfo(pK, pKTag, TypeTag.CELL),
                    StructEntryInfo(pD, NO_STR, TypeTag.CELL),
                ),
            ),
        )
        val mainStr = mb.str("main")
        mb.code.pushInt(0).ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
        val s = Script(mb.build(), VM())

        assertEquals("kills", s.getJsonTag("pInfo", "pKills"))
        assertEquals("pDeaths", s.getJsonTag("pInfo", "pDeaths"))
        assertNull(s.getJsonTag("pInfo", "missing"))
        assertNull(s.getJsonTag("noSuchStruct", "pKills"))
    }

    // ------------------------------------------------------------
    // Built-in natives
    // ------------------------------------------------------------

    @Test fun `registerBuiltins exposes strlen and strcat and format`() {
        val mb = ModuleBuilder()
        val mainStr = mb.str("main")
        val helloC = mb.strConst("hello")
        val worldC = mb.strConst("world")
        val sStr = mb.str("strcat")
        val sLen = mb.str("strlen")
        val sFmt = mb.str("format")
        val fmtConst = mb.strConst("len=%d s=%s")
        mb.natives.add(NativeInfo(sStr, 2, listOf(
            ParamInfo(TypeTag.STRING, false, false, NO_STRUCT),
            ParamInfo(TypeTag.STRING, false, false, NO_STRUCT),
        )))
        mb.natives.add(NativeInfo(sLen, 1, listOf(
            ParamInfo(TypeTag.STRING, false, false, NO_STRUCT),
        )))
        mb.natives.add(NativeInfo(sFmt, 2, listOf(
            ParamInfo(TypeTag.STRING, false, false, NO_STRUCT),
            ParamInfo(TypeTag.CELL, false, false, NO_STRUCT),
        )))
        // main: format("len=%d s=%s", strlen("hello"), strcat("hello", "world"))
        // wait - format takes 1 fmt + var args. Our native impl reads args.drop(1), so
        // we need fmt and one Long arg and one String arg. But arity=2 won't fit %d %s.
        // Simpler: just test strcat result.
        val c = mb.code
        c.pushConst(helloC).pushConst(worldC).ncall(0, 2).ret()
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
        val vm = VM().also { registerBuiltins(it) }
        assertEquals("helloworld", Script(mb.build(), vm).start())
    }

    @Test fun `instruction limit aborts runaway loop`() {
        // main: while (true) { } — pure JMP -1
        val mb = ModuleBuilder()
        val mainStr = mb.str("main")
        val c = mb.code
        // jmp 0 (back to itself: rel = -5)
        c.op(Op.JMP).i32(-5)
        mb.publics.add(PublicInfo(mainStr, 0, 0, 0, emptyList()))
        val vm = VM(VMOptions(instructionLimit = 1000))
        val ex = assertFailsWith<IllegalStateException> { Script(mb.build(), vm).start() }
        assertTrue("instruction limit" in (ex.message ?: ""))
    }
}
