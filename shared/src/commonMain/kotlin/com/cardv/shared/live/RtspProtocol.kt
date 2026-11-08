package com.cardv.shared.live

internal data class RtspResponse(
    val statusCode: Int,
    val reason: String,
    val headers: Map<String, String>,
    val body: String,
) {
    val isSuccess: Boolean get() = statusCode in 200..299

    fun header(name: String): String? = headers[name.lowercase()]
}

internal sealed class Incoming {
    class Frame(val channel: Int, val payload: ByteArray) : Incoming()

    class Response(val response: RtspResponse) : Incoming()
}

/** Growable byte queue with cheap "is a whole message here yet" checks. */
internal class ByteQueue(initialCapacity: Int = 32 * 1024) {
    private var data = ByteArray(initialCapacity)
    private var start = 0
    private var end = 0

    val size: Int get() = end - start

    fun byteAt(index: Int): Int = data[start + index].toInt() and 0xFF

    fun append(source: ByteArray, offset: Int = 0, length: Int = source.size) {
        ensureCapacity(size + length)
        source.copyInto(data, end, offset, offset + length)
        end += length
    }

    fun take(length: Int): ByteArray {
        val out = data.copyOfRange(start, start + length)
        start += length
        if (start == end) {
            start = 0
            end = 0
        }
        return out
    }

    fun copy(length: Int): ByteArray = data.copyOfRange(start, start + length)

    fun drop(length: Int) {
        start = (start + length).coerceAtMost(end)
        if (start == end) {
            start = 0
            end = 0
        }
    }

    fun indexOfByte(value: Int): Int {
        for (i in start until end) {
            if ((data[i].toInt() and 0xFF) == value) return i - start
        }
        return -1
    }

    fun indexOfAscii(text: String): Int {
        if (text.isEmpty() || size < text.length) return -1
        val needle = text.encodeToByteArray()
        outer@ for (i in start..end - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i - start
        }
        return -1
    }

    fun asciiString(offset: Int, length: Int): String =
        data.decodeToString(start + offset, start + offset + length)

    private fun ensureCapacity(required: Int) {
        if (required <= data.size) return
        var capacity = data.size
        while (capacity < required) capacity *= 2
        val grown = ByteArray(capacity)
        data.copyInto(grown, 0, start, end)
        end -= start
        start = 0
        data = grown
    }
}

/**
 * Reads interleaved RTSP: `$`-framed RTP packets and RTSP replies share one TCP connection, and
 * keep-alive replies can arrive in the middle of the video stream.
 */
internal class RtspReader(private val socket: LiveSocket) {
    private val queue = ByteQueue()
    private val scratch = ByteArray(64 * 1024)

    /** Returns `null` when the read timeout expired before a whole message arrived. */
    fun next(): Incoming? {
        while (true) {
            parse()?.let { return it }
            val count = socket.read(scratch, 0, scratch.size)
            when {
                count < 0 -> throw LiveException("The camera ended the live stream.")
                count == 0 -> return null
                else -> {
                    queue.append(scratch, 0, count)
                    if (queue.size > 8 * 1024 * 1024) {
                        throw LiveException("The live stream sent more data than expected.")
                    }
                }
            }
        }
    }

    private fun parse(): Incoming? {
        while (true) {
            if (queue.size < 4) return null
            if (queue.byteAt(0) == '$'.code) {
                val length = (queue.byteAt(2) shl 8) or queue.byteAt(3)
                if (queue.size < 4 + length) return null
                val channel = queue.byteAt(1)
                queue.drop(4)
                return Incoming.Frame(channel, queue.take(length))
            }
            if (queue.indexOfAscii("RTSP/") == 0) {
                val headerEnd = queue.indexOfAscii("\r\n\r\n")
                if (headerEnd < 0) return null
                val headerText = queue.asciiString(0, headerEnd)
                val lines = headerText.split("\r\n")
                val statusParts = lines.firstOrNull()?.split(' ', limit = 3).orEmpty()
                val statusCode = statusParts.getOrNull(1)?.toIntOrNull() ?: -1
                val headers = mutableMapOf<String, String>()
                for (line in lines.drop(1)) {
                    val colon = line.indexOf(':')
                    if (colon <= 0) continue
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
                val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                val total = headerEnd + 4 + contentLength
                if (queue.size < total) return null
                queue.drop(headerEnd + 4)
                val body = if (contentLength > 0) {
                    queue.take(contentLength).decodeToString()
                } else {
                    ""
                }
                return Incoming.Response(
                    RtspResponse(
                        statusCode = statusCode,
                        reason = statusParts.getOrNull(2).orEmpty(),
                        headers = headers,
                        body = body,
                    ),
                )
            }
            // Resynchronise after junk or a partial header. Keep a tail in case a marker spans reads.
            val frame = queue.indexOfByte('$'.code)
            val response = queue.indexOfAscii("RTSP/")
            val first = listOf(frame, response).filter { it > 0 }.minOrNull()
            when {
                first != null -> queue.drop(first)
                frame < 0 && response < 0 -> {
                    // Keep a small tail in case a marker spans two reads, but never spin on it.
                    if (queue.size > 4) {
                        queue.drop(queue.size - 4)
                    } else {
                        return null
                    }
                }

                else -> return null
            }
        }
    }
}

internal fun buildRtspRequest(
    method: String,
    url: String,
    cseq: Int,
    session: String?,
    userAgent: String,
    extraHeaders: List<String> = emptyList(),
    body: String? = null,
): ByteArray {
    val builder = StringBuilder()
    builder.append(method).append(' ').append(url).append(" RTSP/1.0\r\n")
    builder.append("CSeq: ").append(cseq).append("\r\n")
    builder.append("User-Agent: ").append(userAgent).append("\r\n")
    if (session != null) builder.append("Session: ").append(session).append("\r\n")
    for (header in extraHeaders) builder.append(header).append("\r\n")
    if (body != null) builder.append("Content-Length: ").append(body.encodeToByteArray().size).append("\r\n")
    builder.append("\r\n")
    if (body != null) builder.append(body)
    return builder.toString().encodeToByteArray()
}
