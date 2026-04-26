package com.lenerotex.pmx

/**
 * Register the always-available built-in natives on a [vm]. Hosts may override
 * any of these by re-registering with the same name afterwards.
 *
 * Supported: `print`, `println`, `printf`, `format`, `strlen`, `strcat`, `exception`.
 */
fun registerBuiltins(vm: VM) {
    vm.registerNative("print") { args ->
        print(stringify(args.firstOrNull()))
        0L
    }
    vm.registerNative("println") { args ->
        println(stringify(args.firstOrNull()))
        0L
    }
    vm.registerNative("printf") { args ->
        if (args.isEmpty()) {
            println()
        } else {
            val fmt = stringify(args[0])
            println(formatString(fmt, args.drop(1)))
        }
        0L
    }
    vm.registerNative("format") { args ->
        if (args.isEmpty()) "" else formatString(stringify(args[0]), args.drop(1))
    }
    vm.registerNative("strlen") { args ->
        stringify(args.firstOrNull()).toByteArray(Charsets.UTF_8).size.toLong()
    }
    vm.registerNative("strcat") { args ->
        stringify(args.getOrNull(0)) + stringify(args.getOrNull(1))
    }
    vm.registerNative("exception") { args ->
        val code = asLong(args.getOrNull(0)).toInt()
        val msg = stringify(args.getOrNull(1))
        throw PmxScriptException(code, msg)
    }
}

private fun formatString(fmt: String, args: List<Any?>): String {
    val sb = StringBuilder()
    var i = 0
    var ai = 0
    while (i < fmt.length) {
        val c = fmt[i]
        if (c == '%' && i + 1 < fmt.length) {
            i++
            when (val next = fmt[i]) {
                'd' -> sb.append(stringify(args.getOrNull(ai++) ?: 0L))
                'f' -> {
                    val v = args.getOrNull(ai++)
                    val n = when (v) {
                        is Double -> v
                        is Float -> v.toDouble()
                        is Long -> v.toDouble()
                        is Int -> v.toDouble()
                        is String -> v.toDoubleOrNull() ?: Double.NaN
                        null -> 0.0
                        else -> Double.NaN
                    }
                    sb.append(if (n.isNaN()) "NaN" else n.toString())
                }
                's' -> sb.append(stringify(args.getOrNull(ai++) ?: ""))
                'b' -> sb.append(if (asBool(args.getOrNull(ai++))) "true" else "false")
                '%' -> sb.append('%')
                else -> { sb.append('%'); sb.append(next) }
            }
            i++
            continue
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

private fun asBool(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is Long -> v != 0L
    is Int -> v != 0
    is Double -> v != 0.0
    is Float -> v != 0f
    is String -> v.isNotEmpty()
    else -> true
}
