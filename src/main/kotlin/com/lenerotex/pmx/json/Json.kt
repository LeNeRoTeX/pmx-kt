package com.lenerotex.pmx.json

/**
 * Minimal zero-dependency JSON encoder/decoder.
 *
 * Decoded representation:
 * - `null`             -> `null`
 * - `true` / `false`   -> [Boolean]
 * - number             -> [Long] when it parses as an integer that fits in Long, else [Double]
 * - string             -> [String]
 * - array              -> [List]<Any?>
 * - object             -> [LinkedHashMap]<String, Any?>  (insertion-order preserved)
 *
 * The encoder accepts these same types, plus [Map] for objects and [Iterable] / arrays for arrays.
 * Other types fall back to `toString()` quoted as a JSON string.
 */
object Json {
    fun encode(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    fun decode(text: String): Any? {
        val p = JsonParser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.eof()) p.fail("trailing data after JSON value")
        return v
    }

    // --- encoder ---------------------------------------------------------

    private fun writeValue(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Byte, is Short, is Int, is Long -> sb.append(v.toString())
            is Float -> sb.append(formatDouble(v.toDouble()))
            is Double -> sb.append(formatDouble(v))
            is CharSequence -> writeString(sb, v.toString())
            is Char -> writeString(sb, v.toString())
            is Map<*, *> -> writeObject(sb, v)
            is Iterable<*> -> writeArray(sb, v)
            is Array<*> -> writeArray(sb, v.asList())
            is IntArray -> writeArray(sb, v.asList())
            is LongArray -> writeArray(sb, v.asList())
            is DoubleArray -> writeArray(sb, v.asList())
            is FloatArray -> writeArray(sb, v.map { it.toDouble() })
            is BooleanArray -> writeArray(sb, v.asList())
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeArray(sb: StringBuilder, items: Iterable<*>) {
        sb.append('[')
        var first = true
        for (item in items) {
            if (!first) sb.append(',')
            first = false
            writeValue(sb, item)
        }
        sb.append(']')
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            writeString(sb, k?.toString() ?: "null")
            sb.append(':')
            writeValue(sb, v)
        }
        sb.append('}')
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append("%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private fun formatDouble(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == 0.0) return "0"
        if (d == d.toLong().toDouble() && d > -1e16 && d < 1e16) {
            return d.toLong().toString()
        }
        return d.toString()
    }
}

private class JsonParser(val src: String) {
    var i: Int = 0

    fun eof(): Boolean = i >= src.length

    fun skipWs() {
        while (i < src.length) {
            val c = src[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
        }
    }

    fun fail(msg: String): Nothing =
        throw IllegalArgumentException("json: $msg at offset $i")

    fun readValue(): Any? {
        skipWs()
        if (eof()) fail("unexpected end of input")
        return when (val c = src[i]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't', 'f' -> readBool()
            'n' -> readNull()
            '-', in '0'..'9' -> readNumber()
            else -> fail("unexpected char '$c'")
        }
    }

    private fun expect(ch: Char) {
        if (eof() || src[i] != ch) fail("expected '$ch'")
        i++
    }

    private fun readObject(): LinkedHashMap<String, Any?> {
        expect('{')
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (!eof() && src[i] == '}') { i++; return out }
        while (true) {
            skipWs()
            val k = readString()
            skipWs()
            expect(':')
            val v = readValue()
            out[k] = v
            skipWs()
            if (eof()) fail("unterminated object")
            when (src[i]) {
                ',' -> { i++; continue }
                '}' -> { i++; return out }
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readArray(): ArrayList<Any?> {
        expect('[')
        val out = ArrayList<Any?>()
        skipWs()
        if (!eof() && src[i] == ']') { i++; return out }
        while (true) {
            val v = readValue()
            out.add(v)
            skipWs()
            if (eof()) fail("unterminated array")
            when (src[i]) {
                ',' -> { i++; continue }
                ']' -> { i++; return out }
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            if (eof()) fail("unterminated string")
            val c = src[i++]
            if (c == '"') return sb.toString()
            if (c == '\\') {
                if (eof()) fail("bad escape")
                when (val e = src[i++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > src.length) fail("bad \\u escape")
                        val code = src.substring(i, i + 4).toInt(16)
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> fail("bad escape \\$e")
                }
            } else {
                sb.append(c)
            }
        }
    }

    private fun readBool(): Boolean {
        if (src.startsWith("true", i)) { i += 4; return true }
        if (src.startsWith("false", i)) { i += 5; return false }
        fail("expected 'true' or 'false'")
    }

    private fun readNull(): Any? {
        if (src.startsWith("null", i)) { i += 4; return null }
        fail("expected 'null'")
    }

    private fun readNumber(): Any {
        val start = i
        if (src[i] == '-') i++
        while (i < src.length && src[i] in '0'..'9') i++
        var isFloat = false
        if (i < src.length && src[i] == '.') {
            isFloat = true
            i++
            while (i < src.length && src[i] in '0'..'9') i++
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            isFloat = true
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            while (i < src.length && src[i] in '0'..'9') i++
        }
        val text = src.substring(start, i)
        if (!isFloat) {
            text.toLongOrNull()?.let { return it }
        }
        return text.toDouble()
    }
}
