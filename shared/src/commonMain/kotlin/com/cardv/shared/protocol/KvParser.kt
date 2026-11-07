package com.cardv.shared.protocol

/**
 * A parsed `action=get` / `action=set` response.
 *
 * Shape (always HTTP 200):
 * ```
 * 0
 * OK
 * Camera.Menu.EV=EV0
 * ```
 */
data class KvDocument(
    val result: ResultCode,
    val message: String,
    val values: Map<String, String>,
) {
    fun value(key: String): String? = values[key]
}

/**
 * Parser for the line-oriented `result / message / Key=Value` format used by `Config.cgi`.
 *
 * The parser itself never throws for non-zero result codes: callers decide whether a code is an
 * error (writes) or just an empty result. It only rejects bodies that do not start with a number,
 * because those are not command responses at all (for example a directory XML list).
 */
object KvParser {
    fun parse(text: String): KvDocument {
        val lines = text
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')

        val first = lines.firstOrNull()?.trim().orEmpty()
        val code = first.toIntOrNull()
            ?: throw MalformedResponseException(
                "expected a result code on the first line, got: ${first.take(80).ifBlank { "<empty>" }}",
            )

        val message = lines.getOrNull(1)?.trim().orEmpty()
        val values = LinkedHashMap<String, String>()
        for (line in lines.drop(2)) {
            if (line.isBlank()) continue
            val separator = line.indexOf('=')
            if (separator <= 0) continue
            val key = line.substring(0, separator).trim()
            if (key.isEmpty()) continue
            values[key] = line.substring(separator + 1).trim()
        }
        return KvDocument(ResultCode.from(code), message, values)
    }

    /** Convenience for callers that only need the map and do not care about the code. */
    fun parseValues(text: String): Map<String, String> = parse(text).values
}
