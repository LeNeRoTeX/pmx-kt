package com.lenerotex.pmx

/**
 * A struct value as seen by host Kotlin code: a JSON-shaped map keyed by the
 * struct entry's tag string (or its declared name when no tag is present).
 * Used as the [Ref.value] payload for `&name: StructName` parameters.
 */
typealias StructValue = Map<String, Any?>

data class ConstVal(
    val tag: Int,
    val intVal: Long = 0L,
    val fltVal: Double = 0.0,
    val strIdx: Int = NO_STR,
)

data class GlobalInfo(
    val size: Int,
    val initConst: Int,
)

data class StructEntryInfo(
    val nameStr: Int,
    val tagStr: Int,
    val type: Int,
)

data class StructInfo(
    val nameStr: Int,
    val entries: List<StructEntryInfo>,
)

data class ParamInfo(
    val type: Int,
    val isRef: Boolean,
    val isStructArray: Boolean,
    val structIdx: Int,
)

data class NativeInfo(
    val nameStr: Int,
    val arity: Int,
    val params: List<ParamInfo>,
)

data class PublicInfo(
    val nameStr: Int,
    val codeOffset: Int,
    val arity: Int,
    val numLocals: Int,
    val params: List<ParamInfo>,
)

data class FuncInfo(
    val codeOffset: Int,
    val arity: Int,
    val numLocals: Int,
    val params: List<ParamInfo>,
)

data class Module(
    val strings: List<String>,
    val consts: List<ConstVal>,
    val globals: List<GlobalInfo>,
    val structs: List<StructInfo>,
    val natives: List<NativeInfo>,
    val publics: List<PublicInfo>,
    val funcs: List<FuncInfo>,
    val code: ByteArray,
)

/**
 * Mutable box used by the host to pass a value by reference to a `&`-declared
 * public or native parameter. The VM mutates [value] in place after the call
 * returns, so callers can read back the new value. For struct refs
 * (`&name: StructName`), [value] is a [StructValue] (JSON-shaped map). For
 * struct-array refs (`&name[]: StructName`), [value] is a `List<StructValue>`.
 */
class Ref<T>(@JvmField var value: T) {
    override fun toString(): String = "Ref($value)"
}

/** Helper to construct a [Ref] box with an initial value. */
fun <T> ref(initial: T): Ref<T> = Ref(initial)
