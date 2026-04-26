package com.lenerotex.pmx

import java.nio.ByteBuffer
import java.nio.ByteOrder

const val PMX_VERSION: Int = 6

data class ParsedHeader(
    val version: Int,
    val flags: Int,
    val iv: ByteArray,
    val payloadLen: Int,
) {
    val encrypted: Boolean get() = (flags and 1) != 0
}

private class BinReader(val buf: ByteArray, start: Int = 0, end: Int = buf.size) {
    private val bb: ByteBuffer = ByteBuffer.wrap(buf, start, end - start).order(ByteOrder.LITTLE_ENDIAN)
    fun u8(): Int = bb.get().toInt() and 0xff
    fun u16(): Int = bb.short.toInt() and 0xffff
    fun u32(): Int = bb.int   // wire-level u32; consumers treat 0xFFFFFFFF as -1 (NO_*)
    fun i32(): Int = bb.int
    fun i64(): Long = bb.long
    fun f64(): Double = bb.double
    fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        bb.get(out)
        return out
    }

    fun string(n: Int): String = String(bytes(n), Charsets.UTF_8)
    fun position(): Int = bb.position()
}

/** Parse the 24-byte clear header. Throws if the magic or layout is invalid. */
fun parseHeader(buf: ByteArray): ParsedHeader {
    require(buf.size >= 24) { "pmx: file too small" }
    val magic = String(buf, 0, 4, Charsets.US_ASCII)
    require(magic == "PMX1") { "pmx: bad magic \"$magic\"" }
    val r = BinReader(buf, 4, 24)
    val version = r.u16()
    val flags = r.u16()
    val iv = r.bytes(12)
    val payloadLen = r.u32()
    return ParsedHeader(version = version, flags = flags, iv = iv, payloadLen = payloadLen)
}

/** Decode the plaintext payload (post-decryption) into a [Module]. */
fun decodePayload(payload: ByteArray): Module {
    val r = BinReader(payload)

    val sCount = r.u32()
    val strings = ArrayList<String>(sCount)
    repeat(sCount) {
        val ln = r.u32()
        strings.add(r.string(ln))
    }

    val cCount = r.u32()
    val consts = ArrayList<ConstVal>(cCount)
    repeat(cCount) {
        val tag = r.u8()
        val cv = when (tag) {
            ConstTag.INT -> ConstVal(tag, intVal = r.i64())
            ConstTag.FLOAT -> ConstVal(tag, fltVal = r.f64())
            ConstTag.STR_REF -> ConstVal(tag, strIdx = r.u32())
            ConstTag.BOOL -> ConstVal(tag, intVal = r.u8().toLong())
            else -> error("pmx: unknown const tag $tag")
        }
        consts.add(cv)
    }

    val gCount = r.u32()
    val globals = ArrayList<GlobalInfo>(gCount)
    repeat(gCount) { globals.add(GlobalInfo(size = r.u32(), initConst = r.u32())) }

    val eCount = r.u32()
    val structs = ArrayList<StructInfo>(eCount)
    repeat(eCount) {
        val nameStr = r.u32()
        val ec = r.u32()
        val entries = ArrayList<StructEntryInfo>(ec)
        repeat(ec) {
            entries.add(
                StructEntryInfo(
                    nameStr = r.u32(),
                    tagStr = r.u32(),
                    type = r.u8(),
                ),
            )
        }
        structs.add(StructInfo(nameStr = nameStr, entries = entries))
    }

    val nCount = r.u32()
    val natives = ArrayList<NativeInfo>(nCount)
    repeat(nCount) {
        val nameStr = r.u32()
        val arity = r.u8()
        val params = readParams(r, arity)
        natives.add(NativeInfo(nameStr = nameStr, arity = arity, params = params))
    }

    val pCount = r.u32()
    val publics = ArrayList<PublicInfo>(pCount)
    repeat(pCount) {
        val nameStr = r.u32()
        val codeOffset = r.u32()
        val arity = r.u8()
        val numLocals = r.u32()
        val params = readParams(r, arity)
        publics.add(
            PublicInfo(
                nameStr = nameStr,
                codeOffset = codeOffset,
                arity = arity,
                numLocals = numLocals,
                params = params,
            ),
        )
    }

    val fCount = r.u32()
    val funcs = ArrayList<FuncInfo>(fCount)
    repeat(fCount) {
        val codeOffset = r.u32()
        val arity = r.u8()
        val numLocals = r.u32()
        val params = readParams(r, arity)
        funcs.add(
            FuncInfo(
                codeOffset = codeOffset,
                arity = arity,
                numLocals = numLocals,
                params = params,
            ),
        )
    }

    val codeLen = r.u32()
    val code = r.bytes(codeLen)

    return Module(
        strings = strings,
        consts = consts,
        globals = globals,
        structs = structs,
        natives = natives,
        publics = publics,
        funcs = funcs,
        code = code,
    )
}

private fun readParams(r: BinReader, arity: Int): List<ParamInfo> {
    val out = ArrayList<ParamInfo>(arity)
    repeat(arity) {
        val type = r.u8()
        val flags = r.u8()
        val structIdx = r.u32()
        out.add(
            ParamInfo(
                type = type,
                isRef = (flags and PARAM_FLAG_REF) != 0,
                isStructArray = (flags and PARAM_FLAG_STRUCT_ARRAY) != 0,
                structIdx = structIdx,
            ),
        )
    }
    return out
}
