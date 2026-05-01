package com.lenerotex.pmx

import com.lenerotex.pmx.format.ConstTag
import com.lenerotex.pmx.format.NO_CONST
import com.lenerotex.pmx.format.NO_STR
import com.lenerotex.pmx.format.NO_STRUCT
import com.lenerotex.pmx.format.Op
import com.lenerotex.pmx.format.TypeTag
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Script wraps a loaded [Module] bound to a [VM].
 *
 * Cell representations:
 * - `CELL` / `BOOL` cells: [Long]
 * - `FLOAT` cells: [Double]
 * - `STRING` cells: [String]
 */
class Script(
    val module: Module,
    val vm: VM,
    stubMissingNatives: Boolean = false,
) {
    /** Linear heap of cells; globals laid out back-to-back from index 0. */
    private val heap: ArrayList<Any?> = ArrayList()
    private val nativeFns: Array<NativeFn>
    private val publicByName: HashMap<String, Int> = HashMap()
    private val structByName: HashMap<String, Int> = HashMap()
    private val globalBaseByIndex: IntArray
    private val codeBuf: ByteBuffer = ByteBuffer.wrap(module.code).order(ByteOrder.LITTLE_ENDIAN)

    init {
        globalBaseByIndex = IntArray(module.globals.size)
        var total = 0
        for (i in module.globals.indices) {
            globalBaseByIndex[i] = total
            total += module.globals[i].size
        }
        heap.ensureCapacity(total)
        for (i in module.globals.indices) {
            val g = module.globals[i]
            val initVal: Any = if (g.initConst != NO_CONST) constValue(g.initConst) else 0L
            repeat(g.size) { heap.add(initVal) }
        }
        nativeFns = Array(module.natives.size) { i ->
            val name = module.strings[module.natives[i].nameStr]
            val fn = vm.resolve(name)
            when {
                fn != null -> fn
                stubMissingNatives -> {
                    System.err.println("pmx: stubbing unresolved native \"$name\"")
                    val stub: NativeFn = { 0L }
                    stub
                }
                else -> throw IllegalStateException("pmx: unresolved native \"$name\"")
            }
        }
        for (i in module.publics.indices) {
            publicByName[module.strings[module.publics[i].nameStr]] = i
        }
        for (i in module.structs.indices) {
            structByName[module.strings[module.structs[i].nameStr]] = i
        }
    }

    /** Invoke `main()` if present. Returns its result, or `0L` if absent. */
    fun start(): Any =
        if ("main" in publicByName) callPublic("main", emptyList()) else 0L

    /** Read a single cell from the heap by absolute index. */
    fun readCell(addr: Int): Any? = if (addr in heap.indices) heap[addr] else 0L

    /** Returns `(size, base)` per global in declaration order. */
    fun globalLayout(): List<Pair<Int, Int>> =
        module.globals.indices.map { i -> module.globals[i].size to globalBaseByIndex[i] }

    /**
     * Look up the JSON-style string tag for a struct entry, by entry name.
     * Mirrors the PX `getJsonTag(StructName[EntryName])` compile-time intrinsic
     * for host-side tooling. Returns the entry's declared name if it has no
     * explicit tag, and `null` if either the struct or entry is unknown.
     */
    fun getJsonTag(structName: String, entryName: String): String? {
        val ei = structByName[structName] ?: return null
        val e = module.structs[ei]
        for (ent in e.entries) {
            if (module.strings[ent.nameStr] == entryName) {
                return if (ent.tagStr == NO_STR) module.strings[ent.nameStr]
                else module.strings[ent.tagStr]
            }
        }
        return null
    }

    /** Build a JSON-shaped map from a flat struct row at `heap[base..]`. */
    fun serializeStruct(structName: String, base: Int): StructValue {
        val ei = structByName[structName] ?: throw IllegalArgumentException("pmx: unknown struct $structName")
        return serializeStructByIndex(ei, base)
    }

    private fun serializeStructByIndex(structIdx: Int, base: Int): LinkedHashMap<String, Any?> {
        val e = module.structs[structIdx]
        val out = LinkedHashMap<String, Any?>(e.entries.size)
        for (i in e.entries.indices) {
            val ent = e.entries[i]
            val key = if (ent.tagStr == NO_STR) module.strings[ent.nameStr] else module.strings[ent.tagStr]
            out[key] = heapAt(base + i)
        }
        return out
    }

    private fun heapAt(addr: Int): Any =
        if (addr in heap.indices) (heap[addr] ?: 0L) else 0L

    // ============================================================
    // Public dispatch (sets up ref boxes, runs the function, writes back)
    // ============================================================

    fun callPublic(name: String, args: List<Any?> = emptyList()): Any {
        val idx = publicByName[name] ?: throw IllegalArgumentException("pmx: no public \"$name\"")
        val p = module.publics[idx]
        require(args.size == p.arity) { "pmx: $name expects ${p.arity} args, got ${args.size}" }

        val heapBaseSnapshot = heap.size
        val refSlots: Array<RefSlot?> = arrayOfNulls(args.size)
        val bound = arrayOfNulls<Any?>(args.size)

        for (i in args.indices) {
            val param = p.params.getOrNull(i) ?: DEFAULT_PARAM
            val a = args[i]
            if (param.isRef) {
                require(a is Ref<*>) {
                    "pmx: callPublic: $name arg ${i + 1} must be a Ref { value } box (declared with &)"
                }
                @Suppress("UNCHECKED_CAST")
                val box = a as Ref<Any?>
                val addr = heap.size
                heap.add(coerceValue(box.value, param.type))
                refSlots[i] = RefSlot(box, addr, param.type)
                bound[i] = addr.toLong()
            } else {
                require(a !is Ref<*>) {
                    "pmx: callPublic: $name arg ${i + 1} is a value parameter; pass the value directly, not a Ref box"
                }
                bound[i] = a
            }
        }

        try {
            val ret = run(p.codeOffset, p.numLocals, bound)
            for (i in refSlots.indices) {
                val slot = refSlots[i] ?: continue
                @Suppress("UNCHECKED_CAST")
                slot.box.value = coerceValue(heap[slot.addr], slot.type)
            }
            return ret
        } finally {
            shrinkHeap(heapBaseSnapshot)
        }
    }

    private class RefSlot(val box: Ref<Any?>, val addr: Int, val type: Int)

    private fun shrinkHeap(target: Int) {
        while (heap.size > target) heap.removeAt(heap.size - 1)
    }

    private fun constValue(idx: Int): Any {
        val c = module.consts[idx]
        return when (c.tag) {
            ConstTag.INT, ConstTag.BOOL -> c.intVal
            ConstTag.FLOAT -> c.fltVal
            ConstTag.STR_REF -> module.strings[c.strIdx]
            else -> error("pmx: bad const tag ${c.tag}")
        }
    }

    // ============================================================
    // Run loop
    // ============================================================

    private fun run(entry: Int, numLocals: Int, args: Array<Any?>): Any {
        val stack = ArrayDeque<Any?>()
        data class Frame(val locals: Array<Any?>, val ret: Int, val heapBase: Int)

        val locals0 = arrayOfNulls<Any?>(maxOf(numLocals, args.size))
        for (i in locals0.indices) locals0[i] = if (i < args.size) (args[i] ?: 0L) else 0L

        val frames = ArrayDeque<Frame>()
        var curLocals: Array<Any?> = locals0
        var curHeapBase = heap.size
        var pc = entry
        var inst = 0L
        val limit = vm.options.instructionLimit
        val code = module.code

        while (true) {
            if (++inst > limit) throw IllegalStateException("pmx: instruction limit $limit exceeded")
            val op = (code[pc].toInt() and 0xff)
            pc += 1
            when (op) {
                Op.NOP -> { }
                Op.HALT -> return if (stack.isNotEmpty()) (stack.last() ?: 0L) else 0L
                Op.PUSH_CONST -> {
                    val idx = readU32(pc); pc += 4
                    stack.addLast(constValue(idx))
                }
                Op.PUSH_INT -> {
                    val v = readI32(pc); pc += 4
                    stack.addLast(v.toLong())
                }
                Op.POP -> stack.removeLast()
                Op.DUP -> stack.addLast(stack.last())
                Op.SWAP -> {
                    val a = stack.removeLast()
                    val b = stack.removeLast()
                    stack.addLast(a)
                    stack.addLast(b)
                }
                Op.LOAD_LOCAL -> {
                    val slot = readU16(pc); pc += 2
                    stack.addLast(curLocals.getOrNull(slot) ?: 0L)
                }
                Op.STORE_LOCAL -> {
                    val slot = readU16(pc); pc += 2
                    curLocals[slot] = stack.removeLast()
                }
                Op.GLOBAL_ADDR -> {
                    val slot = readU32(pc); pc += 4
                    stack.addLast(slot.toLong())
                }
                Op.LOAD_HEAP -> {
                    val addr = asLong(stack.removeLast()).toInt()
                    stack.addLast(heapAt(addr))
                }
                Op.STORE_HEAP -> {
                    val value = stack.removeLast()
                    val addr = asLong(stack.removeLast()).toInt()
                    setHeap(addr, value)
                }
                Op.INC_HEAP -> {
                    val addr = asLong(stack.removeLast()).toInt()
                    setHeap(addr, asLong(heapAt(addr)) + 1L)
                }
                Op.DEC_HEAP -> {
                    val addr = asLong(stack.removeLast()).toInt()
                    setHeap(addr, asLong(heapAt(addr)) - 1L)
                }
                Op.HEAP_ALLOC1 -> {
                    val addr = heap.size
                    heap.add(0L)
                    stack.addLast(addr.toLong())
                }
                Op.ADD -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(when {
                        a is String || b is String -> a.toString() + b.toString()
                        a is Double || b is Double -> asDouble(a) + asDouble(b)
                        else -> asLong(a) + asLong(b)
                    })
                }
                Op.SUB -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(if (a is Double || b is Double) asDouble(a) - asDouble(b) else asLong(a) - asLong(b))
                }
                Op.MUL -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(if (a is Double || b is Double) asDouble(a) * asDouble(b) else asLong(a) * asLong(b))
                }
                Op.DIV -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    if (a is Double || b is Double) {
                        stack.addLast(asDouble(a) / asDouble(b))
                    } else {
                        val bl = asLong(b)
                        if (bl == 0L) throw ArithmeticException("pmx: integer division by zero")
                        stack.addLast(asLong(a) / bl)
                    }
                }
                Op.MOD -> {
                    val bl = asLong(stack.removeLast())
                    val al = asLong(stack.removeLast())
                    if (bl == 0L) throw ArithmeticException("pmx: modulo by zero")
                    stack.addLast(al % bl)
                }
                Op.NEG -> {
                    val a = stack.removeLast()
                    stack.addLast(if (a is Double) -a else -asLong(a))
                }
                Op.FADD, Op.FSUB, Op.FMUL, Op.FDIV -> {
                    val b = asDouble(stack.removeLast())
                    val a = asDouble(stack.removeLast())
                    stack.addLast(when (op) {
                        Op.FADD -> a + b
                        Op.FSUB -> a - b
                        Op.FMUL -> a * b
                        else -> a / b
                    })
                }
                Op.EQ, Op.NE, Op.LT, Op.LE, Op.GT, Op.GE -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    val r = compareCells(op, a, b)
                    stack.addLast(if (r) 1L else 0L)
                }
                Op.BAND -> {
                    val bl = asLong(stack.removeLast()); val al = asLong(stack.removeLast())
                    stack.addLast(al and bl)
                }
                Op.BOR -> {
                    val bl = asLong(stack.removeLast()); val al = asLong(stack.removeLast())
                    stack.addLast(al or bl)
                }
                Op.BXOR -> {
                    val bl = asLong(stack.removeLast()); val al = asLong(stack.removeLast())
                    stack.addLast(al xor bl)
                }
                Op.BNOT -> stack.addLast(asLong(stack.removeLast()).inv())
                Op.SHL -> {
                    val bl = asLong(stack.removeLast()); val al = asLong(stack.removeLast())
                    stack.addLast(al shl (bl.toInt() and 63))
                }
                Op.SHR -> {
                    val bl = asLong(stack.removeLast()); val al = asLong(stack.removeLast())
                    stack.addLast(al shr (bl.toInt() and 63))
                }
                Op.LNOT -> stack.addLast(if (isNonZero(stack.removeLast())) 0L else 1L)
                Op.JMP -> {
                    val rel = readI32(pc); pc += 4; pc += rel
                }
                Op.JZ -> {
                    val rel = readI32(pc); pc += 4
                    if (!isNonZero(stack.removeLast())) pc += rel
                }
                Op.JNZ -> {
                    val rel = readI32(pc); pc += 4
                    if (isNonZero(stack.removeLast())) pc += rel
                }
                Op.CALL -> {
                    val fn = readU32(pc); pc += 4
                    val argc = readU8(pc); pc += 1
                    require(fn in module.funcs.indices) { "pmx: bad CALL $fn" }
                    val f = module.funcs[fn]
                    val newLocals = arrayOfNulls<Any?>(maxOf(f.numLocals, argc))
                    for (i in 0 until argc) newLocals[argc - 1 - i] = stack.removeLast()
                    for (i in argc until newLocals.size) newLocals[i] = 0L
                    frames.addLast(Frame(curLocals, pc, curHeapBase))
                    curLocals = newLocals
                    curHeapBase = heap.size
                    pc = f.codeOffset
                }
                Op.RET -> {
                    val ret = if (stack.isNotEmpty()) (stack.removeLast() ?: 0L) else 0L
                    shrinkHeap(curHeapBase)
                    if (frames.isEmpty()) return ret
                    val fr = frames.removeLast()
                    curLocals = fr.locals
                    curHeapBase = fr.heapBase
                    pc = fr.ret
                    stack.addLast(ret)
                }
                Op.NCALL -> {
                    val ni = readU32(pc); pc += 4
                    val argc = readU8(pc); pc += 1
                    require(ni in nativeFns.indices) { "pmx: bad NCALL $ni" }
                    runNative(ni, argc, stack)
                }
                Op.STR_CONCAT -> {
                    val b = stack.removeLast()
                    val a = stack.removeLast()
                    stack.addLast(stringify(a) + stringify(b))
                }
                Op.TO_FLOAT -> stack.addLast(asLong(stack.removeLast()).toDouble())
                Op.TO_INT -> {
                    val v = stack.removeLast()
                    stack.addLast(when (v) {
                        is Long -> v
                        is Double -> v.toLong()
                        is Int -> v.toLong()
                        else -> asLong(v)
                    })
                }
                Op.SERIALIZE -> {
                    val structIdx = readU32(pc); pc += 4
                    val base = asLong(stack.removeLast()).toInt()
                    stack.addLast(runSerialize(structIdx, base))
                }
                Op.DESERIALIZE -> {
                    val structIdx = readU32(pc); pc += 4
                    val validate = isNonZero(stack.removeLast())
                    val payload = stringify(stack.removeLast())
                    val base = asLong(stack.removeLast()).toInt()
                    runDeserialize(structIdx, base, payload, validate)
                    stack.addLast(1L)
                }
                Op.SERIALIZE_ARRAY -> {
                    val structIdx = readU32(pc); pc += 4
                    val length = asLong(stack.removeLast()).toInt()
                    val base = asLong(stack.removeLast()).toInt()
                    stack.addLast(runSerializeArray(structIdx, base, length))
                }
                Op.DESERIALIZE_ARRAY -> {
                    val structIdx = readU32(pc); pc += 4
                    val validate = isNonZero(stack.removeLast())
                    val payload = stringify(stack.removeLast())
                    val length = asLong(stack.removeLast()).toInt()
                    val base = asLong(stack.removeLast()).toInt()
                    runDeserializeArray(structIdx, base, length, payload, validate)
                    stack.addLast(1L)
                }
                else -> throw IllegalStateException("pmx: unknown opcode 0x${op.toString(16)} at pc ${pc - 1}")
            }
        }
    }

    // ============================================================
    // NCALL dispatch
    // ============================================================

    private sealed class RawArg {
        class Value(val v: Any?) : RawArg()
        class ScalarRef(val addr: Int, val param: ParamInfo) : RawArg()
        class StructRef(val addr: Int, val structIdx: Int, val param: ParamInfo) : RawArg()
        class StructArrayRef(val addr: Int, val length: Int, val structIdx: Int, val param: ParamInfo) : RawArg()
    }

    private fun runNative(ni: Int, argc: Int, stack: ArrayDeque<Any?>) {
        val nat = module.natives[ni]
        val raws = arrayOfNulls<RawArg>(argc)
        // Pop in reverse declared order; struct-array refs consume two stack cells
        // (length on top, base address below); other refs/values consume one.
        for (i in argc - 1 downTo 0) {
            val p = nat.params.getOrNull(i) ?: DEFAULT_PARAM
            raws[i] = when {
                p.isRef && p.isStructArray -> {
                    val length = asLong(stack.removeLast()).toInt()
                    val addr = asLong(stack.removeLast()).toInt()
                    val structIdx = asLong(stack.removeLast()).toInt()
                    RawArg.StructArrayRef(addr, length, structIdx, p)
                }
                p.isRef && p.type == TypeTag.STRUCT -> {
                    val addr = asLong(stack.removeLast()).toInt()
                    val structIdx = asLong(stack.removeLast()).toInt()
                    RawArg.StructRef(addr, structIdx, p)
                }
                p.isRef -> {
                    val addr = asLong(stack.removeLast()).toInt()
                    RawArg.ScalarRef(addr, p)
                }
                else -> RawArg.Value(stack.removeLast())
            }
        }

        val refSlots = arrayOfNulls<Pair<Ref<Any?>, RawArg>>(argc)
        val bound = arrayOfNulls<Any?>(argc)
        for (i in 0 until argc) {
            val ra = raws[i]!!
            when (ra) {
                is RawArg.Value -> bound[i] = ra.v
                is RawArg.ScalarRef -> {
                    val box = Ref<Any?>(coerceValue(heapAt(ra.addr), ra.param.type))
                    refSlots[i] = box to ra
                    bound[i] = box
                }
                is RawArg.StructRef -> {
                    val box = Ref<Any?>(serializeStructByIndex(ra.structIdx, ra.addr))
                    refSlots[i] = box to ra
                    bound[i] = box
                }
                is RawArg.StructArrayRef -> {
                    val e = module.structs[ra.structIdx]
                    val entryCount = e.entries.size
                    val rows = ArrayList<StructValue>(ra.length)
                    for (k in 0 until ra.length) {
                        rows.add(serializeStructByIndex(ra.structIdx, ra.addr + k * entryCount))
                    }
                    val box = Ref<Any?>(rows)
                    refSlots[i] = box to ra
                    bound[i] = box
                }
            }
        }

        val r = nativeFns[ni].invoke(bound.toList())

        for (i in 0 until argc) {
            val slot = refSlots[i] ?: continue
            val (box, ra) = slot
            when (ra) {
                is RawArg.ScalarRef -> setHeap(ra.addr, coerceValue(box.value, ra.param.type))
                is RawArg.StructRef -> writeStructFromObject(ra.structIdx, ra.addr, box.value)
                is RawArg.StructArrayRef -> {
                    val v = box.value
                    require(v is List<*>) { "pmx: native struct[] ref: box value must be a List" }
                    val e = module.structs[ra.structIdx]
                    val entryCount = e.entries.size
                    val n = minOf(v.size, ra.length)
                    for (k in 0 until n) {
                        @Suppress("UNCHECKED_CAST")
                        writeStructFromObject(ra.structIdx, ra.addr + k * entryCount, v[k])
                    }
                }
                else -> { /* unreachable */ }
            }
        }

        stack.addLast(r ?: 0L)
    }

    // ============================================================
    // SERIALIZE / DESERIALIZE
    // ============================================================

    private fun runSerialize(structIdx: Int, base: Int): String =
        vm.options.jsonCodec.encode(serializePlain(structIdx, base))

    private fun runSerializeArray(structIdx: Int, base: Int, length: Int): String {
        val e = module.structs[structIdx]
        val entryCount = e.entries.size
        val rows = ArrayList<Map<String, Any?>>(length)
        for (i in 0 until length) rows.add(serializePlain(structIdx, base + i * entryCount))
        return vm.options.jsonCodec.encode(rows)
    }

    private fun serializePlain(structIdx: Int, base: Int): LinkedHashMap<String, Any?> {
        val e = module.structs[structIdx]
        val out = LinkedHashMap<String, Any?>(e.entries.size)
        for (i in e.entries.indices) {
            val ent = e.entries[i]
            val key = if (ent.tagStr == NO_STR) module.strings[ent.nameStr] else module.strings[ent.tagStr]
            out[key] = serializeValue(heapAt(base + i))
        }
        return out
    }

    private fun runDeserialize(structIdx: Int, base: Int, payload: String, validate: Boolean) {
        val obj = try {
            vm.options.jsonCodec.decode(payload)
        } catch (e: Exception) {
            throw IllegalStateException("pmx: deserialize: invalid JSON payload (${e.message})", e)
        }
        if (obj !is Map<*, *>) throw IllegalStateException("pmx: deserialize: payload must be a JSON object")
        @Suppress("UNCHECKED_CAST")
        writeStructFromJsonObject(structIdx, base, obj as Map<String, Any?>, validate)
    }

    private fun runDeserializeArray(structIdx: Int, base: Int, length: Int, payload: String, validate: Boolean) {
        val arr = try {
            vm.options.jsonCodec.decode(payload)
        } catch (e: Exception) {
            throw IllegalStateException("pmx: deserialize: invalid JSON payload (${e.message})", e)
        }
        if (arr !is List<*>) throw IllegalStateException("pmx: deserialize: payload must be a JSON array")
        if (validate && arr.size != length) {
            throw IllegalStateException("pmx: deserialize: expected $length rows, got ${arr.size}")
        }
        val e = module.structs[structIdx]
        val entryCount = e.entries.size
        val n = minOf(arr.size, length)
        for (i in 0 until n) {
            val row = arr[i]
            if (row !is Map<*, *>) {
                throw IllegalStateException("pmx: deserialize: array element $i must be a JSON object")
            }
            @Suppress("UNCHECKED_CAST")
            writeStructFromJsonObject(structIdx, base + i * entryCount, row as Map<String, Any?>, validate)
        }
    }

    private fun writeStructFromJsonObject(
        structIdx: Int,
        base: Int,
        o: Map<String, Any?>,
        validate: Boolean,
    ) {
        val e = module.structs[structIdx]
        for (i in e.entries.indices) {
            val ent = e.entries[i]
            val key = if (ent.tagStr == NO_STR) module.strings[ent.nameStr] else module.strings[ent.tagStr]
            if (!o.containsKey(key)) {
                if (validate) throw IllegalStateException("pmx: deserialize: missing key '$key'")
                continue
            }
            setHeap(base + i, coerceValue(o[key], ent.type))
        }
    }

    private fun writeStructFromObject(structIdx: Int, base: Int, obj: Any?) {
        if (obj == null) return
        require(obj is Map<*, *>) { "pmx: native struct ref: box value must be a Map" }
        val e = module.structs[structIdx]
        for (i in e.entries.indices) {
            val ent = e.entries[i]
            val key = if (ent.tagStr == NO_STR) module.strings[ent.nameStr] else module.strings[ent.tagStr]
            if (!obj.containsKey(key)) continue
            setHeap(base + i, coerceValue(obj[key], ent.type))
        }
    }

    private fun setHeap(addr: Int, v: Any?) {
        while (heap.size <= addr) heap.add(0L)
        heap[addr] = v
    }

    // ============================================================
    // Bytecode reader helpers (little-endian)
    // ============================================================

    private fun readU8(off: Int): Int = module.code[off].toInt() and 0xff
    private fun readU16(off: Int): Int = codeBuf.getShort(off).toInt() and 0xffff
    private fun readU32(off: Int): Int = codeBuf.getInt(off)
    private fun readI32(off: Int): Int = codeBuf.getInt(off)

    companion object {
        internal val DEFAULT_PARAM = ParamInfo(
            type = TypeTag.CELL,
            isRef = false,
            isStructArray = false,
            structIdx = NO_STRUCT,
        )
    }
}

// ============================================================
// Cell helpers (file-private)
// ============================================================

internal fun asLong(v: Any?): Long = when (v) {
    null -> 0L
    is Long -> v
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is Boolean -> if (v) 1L else 0L
    is Double -> v.toLong()
    is Float -> v.toLong()
    is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.toLong() ?: 0L
    else -> 0L
}

internal fun asDouble(v: Any?): Double = when (v) {
    null -> 0.0
    is Double -> v
    is Float -> v.toDouble()
    is Long -> v.toDouble()
    is Int -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> v.trim().toDoubleOrNull() ?: 0.0
    else -> 0.0
}

internal fun isNonZero(v: Any?): Boolean = when (v) {
    null -> false
    is Long -> v != 0L
    is Int -> v != 0
    is Double -> v != 0.0
    is Float -> v != 0f
    is Boolean -> v
    is String -> v.isNotEmpty()
    else -> true
}

internal fun stringify(v: Any?): String = when (v) {
    null -> ""
    is String -> v
    is Long, is Int, is Short, is Byte -> v.toString()
    is Double -> {
        if (v.isFinite() && v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }
    is Float -> stringify(v.toDouble())
    is Boolean -> if (v) "true" else "false"
    else -> v.toString()
}

internal fun compareCells(op: Int, a: Any?, b: Any?): Boolean {
    if (a is String || b is String) {
        val sa = stringify(a); val sb = stringify(b)
        return when (op) {
            Op.EQ -> sa == sb
            Op.NE -> sa != sb
            Op.LT -> sa < sb
            Op.LE -> sa <= sb
            Op.GT -> sa > sb
            else -> sa >= sb
        }
    }
    if (a is Double || b is Double) {
        val na = asDouble(a); val nb = asDouble(b)
        return when (op) {
            Op.EQ -> na == nb
            Op.NE -> na != nb
            Op.LT -> na < nb
            Op.LE -> na <= nb
            Op.GT -> na > nb
            else -> na >= nb
        }
    }
    val la = asLong(a); val lb = asLong(b)
    return when (op) {
        Op.EQ -> la == lb
        Op.NE -> la != lb
        Op.LT -> la < lb
        Op.LE -> la <= lb
        Op.GT -> la > lb
        else -> la >= lb
    }
}

/** Coerce a raw value to the canonical cell type for a declared param/entry type. */
internal fun coerceValue(v: Any?, type: Int): Any = when (type) {
    TypeTag.STRING -> if (v == null) "" else stringify(v)
    TypeTag.FLOAT -> asDouble(v)
    TypeTag.BOOL -> if (coerceBool(v)) 1L else 0L
    TypeTag.CELL -> asLong(v)
    else -> asLong(v)
}

private fun coerceBool(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Long -> v != 0L
    is Int -> v != 0
    is Double -> v != 0.0
    is Float -> v != 0f
    is String -> when {
        v == "true" -> true
        v == "false" || v.isEmpty() -> false
        else -> v.toDoubleOrNull()?.let { it != 0.0 } ?: false
    }
    else -> true
}

private fun serializeValue(v: Any?): Any? = when (v) {
    null -> 0L
    is Long -> v
    is Double, is Float -> v
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is Boolean -> if (v) 1L else 0L
    else -> v
}
