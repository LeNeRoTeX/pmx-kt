package com.lenerotex.pmx

import com.lenerotex.pmx.format.ConstTag
import com.lenerotex.pmx.format.Op
import com.lenerotex.pmx.format.PARAM_FLAG_REF
import com.lenerotex.pmx.format.PARAM_FLAG_STRUCT_ARRAY
import com.lenerotex.pmx.format.PMX_VERSION
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Test helper for emitting in-memory PMX modules and payload byte streams
 * without going through the Go compiler.
 */
internal class ModuleBuilder {
    val strings = ArrayList<String>()
    private val stringIndex = HashMap<String, Int>()
    val consts = ArrayList<ConstVal>()
    val globals = ArrayList<GlobalInfo>()
    val structs = ArrayList<StructInfo>()
    val natives = ArrayList<NativeInfo>()
    val publics = ArrayList<PublicInfo>()
    val funcs = ArrayList<FuncInfo>()
    val code = CodeBuilder()

    fun str(s: String): Int = stringIndex.getOrPut(s) {
        strings.add(s)
        strings.size - 1
    }

    fun intConst(v: Long): Int {
        consts.add(ConstVal(tag = ConstTag.INT, intVal = v))
        return consts.size - 1
    }

    fun strConst(s: String): Int {
        val idx = str(s)
        consts.add(ConstVal(tag = ConstTag.STR_REF, strIdx = idx))
        return consts.size - 1
    }

    fun build(): Module = Module(
        strings = strings.toList(),
        consts = consts.toList(),
        globals = globals.toList(),
        structs = structs.toList(),
        natives = natives.toList(),
        publics = publics.toList(),
        funcs = funcs.toList(),
        code = code.toBytes(),
    )

    fun encodePayload(): ByteArray {
        val out = ByteArrayOutputStream()
        val w = LeWriter(out)
        w.u32(strings.size)
        for (s in strings) {
            val bytes = s.toByteArray(Charsets.UTF_8)
            w.u32(bytes.size)
            w.bytes(bytes)
        }
        w.u32(consts.size)
        for (c in consts) {
            w.u8(c.tag)
            when (c.tag) {
                ConstTag.INT -> w.i64(c.intVal)
                ConstTag.FLOAT -> w.f64(c.fltVal)
                ConstTag.STR_REF -> w.u32(c.strIdx)
                ConstTag.BOOL -> w.u8((c.intVal and 0xff).toInt())
            }
        }
        w.u32(globals.size)
        for (g in globals) { w.u32(g.size); w.u32(g.initConst) }
        w.u32(structs.size)
        for (s in structs) {
            w.u32(s.nameStr); w.u32(s.entries.size)
            for (e in s.entries) {
                w.u32(e.nameStr); w.u32(e.tagStr); w.u8(e.type)
            }
        }
        w.u32(natives.size)
        for (n in natives) {
            w.u32(n.nameStr); w.u8(n.arity)
            writeParams(w, n.params)
        }
        w.u32(publics.size)
        for (p in publics) {
            w.u32(p.nameStr); w.u32(p.codeOffset); w.u8(p.arity); w.u32(p.numLocals)
            writeParams(w, p.params)
        }
        w.u32(funcs.size)
        for (f in funcs) {
            w.u32(f.codeOffset); w.u8(f.arity); w.u32(f.numLocals)
            writeParams(w, f.params)
        }
        val codeBytes = code.toBytes()
        w.u32(codeBytes.size)
        w.bytes(codeBytes)
        return out.toByteArray()
    }

    private fun writeParams(w: LeWriter, params: List<ParamInfo>) {
        for (p in params) {
            w.u8(p.type)
            var flags = 0
            if (p.isRef) flags = flags or PARAM_FLAG_REF
            if (p.isStructArray) flags = flags or PARAM_FLAG_STRUCT_ARRAY
            w.u8(flags)
            w.u32(p.structIdx)
        }
    }
}

internal class CodeBuilder {
    private val out = ByteArrayOutputStream()
    private val w = LeWriter(out)

    fun pos(): Int = out.size()

    fun toBytes(): ByteArray = out.toByteArray()

    fun op(op: Int): CodeBuilder = apply { w.u8(op) }
    fun u8(v: Int): CodeBuilder = apply { w.u8(v) }
    fun u16(v: Int): CodeBuilder = apply { w.u16(v) }
    fun u32(v: Int): CodeBuilder = apply { w.u32(v) }
    fun i32(v: Int): CodeBuilder = apply { w.i32(v) }

    fun pushInt(v: Int): CodeBuilder = op(Op.PUSH_INT).i32(v)
    fun pushConst(idx: Int): CodeBuilder = op(Op.PUSH_CONST).u32(idx)
    fun loadLocal(slot: Int): CodeBuilder = op(Op.LOAD_LOCAL).u16(slot)
    fun storeLocal(slot: Int): CodeBuilder = op(Op.STORE_LOCAL).u16(slot)
    fun globalAddr(slot: Int): CodeBuilder = op(Op.GLOBAL_ADDR).u32(slot)
    fun call(fn: Int, argc: Int): CodeBuilder = op(Op.CALL).u32(fn).u8(argc)
    fun ncall(ni: Int, argc: Int): CodeBuilder = op(Op.NCALL).u32(ni).u8(argc)
    fun jmp(rel: Int): CodeBuilder = op(Op.JMP).i32(rel)
    fun jz(rel: Int): CodeBuilder = op(Op.JZ).i32(rel)
    fun jnz(rel: Int): CodeBuilder = op(Op.JNZ).i32(rel)
    fun ret(): CodeBuilder = op(Op.RET)
    fun halt(): CodeBuilder = op(Op.HALT)

    fun patchI32(off: Int, v: Int) {
        val bb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(off, v)
        // ByteArrayOutputStream has no direct in-place patch; rebuild.
        val bytes = bb.array()
        out.reset()
        out.write(bytes)
    }
}

internal class LeWriter(val out: ByteArrayOutputStream) {
    fun u8(v: Int) { out.write(v and 0xff) }
    fun u16(v: Int) { out.write(v and 0xff); out.write((v ushr 8) and 0xff) }
    fun u32(v: Int) {
        out.write(v and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 24) and 0xff)
    }
    fun i32(v: Int) = u32(v)
    fun i64(v: Long) {
        u32(v.toInt())
        u32((v ushr 32).toInt())
    }
    fun f64(v: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(v)
        i64(bits)
    }
    fun bytes(b: ByteArray) { out.write(b) }
}

/** Wrap a payload with a clear (unencrypted) PMX header. */
internal fun wrapHeader(payload: ByteArray, version: Int = PMX_VERSION, encrypted: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("PMX1".toByteArray(Charsets.US_ASCII))
    val w = LeWriter(out)
    w.u16(version)
    w.u16(if (encrypted) 1 else 0)
    repeat(12) { out.write(0) }
    w.u32(payload.size)
    out.write(payload)
    return out.toByteArray()
}
