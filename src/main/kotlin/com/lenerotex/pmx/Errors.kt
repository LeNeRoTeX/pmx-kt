package com.lenerotex.pmx

/**
 * Raised by the script-level `exception(code, message)` native. Hosts can
 * catch this to distinguish a script-raised abort from any other runtime
 * failure (decoder error, instruction limit, etc.).
 */
class PmxScriptException(
    val code: Int,
    message: String,
) : RuntimeException(message)
