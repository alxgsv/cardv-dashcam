package com.cardv.shared.live

/** `rtsp://host[:port]/path`, parsed by hand so the shared core needs no URL library. */
internal data class RtspUrl(
    val host: String,
    val port: Int,
    val path: String,
    val original: String,
) {
    /** The request URL used for OPTIONS/DESCRIBE, without a trailing slash. */
    val requestUrl: String
        get() = "rtsp://$host:$port/${path.trimStart('/')}"

    companion object {
        fun parse(raw: String): RtspUrl {
            val text = raw.trim()
            val prefix = "rtsp://"
            if (!text.startsWith(prefix, ignoreCase = true)) {
                throw LiveException("Not an RTSP address: $raw")
            }
            val rest = text.substring(prefix.length)
            val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
                .let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            if (authority.isEmpty() || authority.startsWith(":")) {
                throw LiveException("The RTSP address has no host: $raw")
            }
            val portSeparator = authority.lastIndexOf(':')
            val host = if (portSeparator >= 0) authority.substring(0, portSeparator) else authority
            val portText = if (portSeparator >= 0) authority.substring(portSeparator + 1) else ""
            val port = if (portText.isEmpty()) 554 else portText.toIntOrNull()
                ?: throw LiveException("Invalid RTSP port in $raw")
            if (port !in 1..65535) throw LiveException("Invalid RTSP port in $raw")
            if (host.isEmpty()) throw LiveException("The RTSP address has no host: $raw")
            val path = rest.substring(authorityEnd).substringBefore('?').substringBefore('#')
            return RtspUrl(host = host, port = port, path = path.ifEmpty { "/" }, original = raw)
        }
    }
}
