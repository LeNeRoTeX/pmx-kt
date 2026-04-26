package com.lenerotex.pmx

/** Mirrors compiler/internal/compile/opcodes.go and runtime/src/opcodes.ts. */
object Op {
    const val NOP: Int = 0x00
    const val HALT: Int = 0x01
    const val PUSH_CONST: Int = 0x02
    const val PUSH_INT: Int = 0x03
    const val POP: Int = 0x04
    const val DUP: Int = 0x05
    const val SWAP: Int = 0x06
    const val LOAD_LOCAL: Int = 0x10
    const val STORE_LOCAL: Int = 0x11
    const val GLOBAL_ADDR: Int = 0x12
    const val LOAD_HEAP: Int = 0x13
    const val STORE_HEAP: Int = 0x14
    const val INC_HEAP: Int = 0x15
    const val DEC_HEAP: Int = 0x16
    const val HEAP_ALLOC1: Int = 0x17
    const val ADD: Int = 0x30
    const val SUB: Int = 0x31
    const val MUL: Int = 0x32
    const val DIV: Int = 0x33
    const val MOD: Int = 0x34
    const val NEG: Int = 0x35
    const val FADD: Int = 0x40
    const val FSUB: Int = 0x41
    const val FMUL: Int = 0x42
    const val FDIV: Int = 0x43
    const val EQ: Int = 0x50
    const val NE: Int = 0x51
    const val LT: Int = 0x52
    const val LE: Int = 0x53
    const val GT: Int = 0x54
    const val GE: Int = 0x55
    const val BAND: Int = 0x60
    const val BOR: Int = 0x61
    const val BXOR: Int = 0x62
    const val BNOT: Int = 0x63
    const val SHL: Int = 0x64
    const val SHR: Int = 0x65
    const val LNOT: Int = 0x66
    const val JMP: Int = 0x70
    const val JZ: Int = 0x71
    const val JNZ: Int = 0x72
    const val CALL: Int = 0x80
    const val RET: Int = 0x81
    const val NCALL: Int = 0x82
    const val STR_CONCAT: Int = 0xa0
    const val TO_FLOAT: Int = 0xb0
    const val TO_INT: Int = 0xb1
    const val SERIALIZE: Int = 0xc0
    const val DESERIALIZE: Int = 0xc1
    const val SERIALIZE_ARRAY: Int = 0xc2
    const val DESERIALIZE_ARRAY: Int = 0xc3
}

object ConstTag {
    const val INT: Int = 0
    const val FLOAT: Int = 1
    const val STR_REF: Int = 2
    const val BOOL: Int = 3
}

object TypeTag {
    const val CELL: Int = 0
    const val FLOAT: Int = 1
    const val STRING: Int = 2
    const val BOOL: Int = 3
    const val STRUCT: Int = 4
}

const val NO_CONST: Int = -1   // 0xFFFFFFFF as signed Int
const val NO_STR: Int = -1
const val NO_STRUCT: Int = -1

const val PARAM_FLAG_REF: Int = 1
const val PARAM_FLAG_STRUCT_ARRAY: Int = 2
