package com.cardv.shared.live

/**
 * The small SDP subset an RTSP live stream needs: track URLs, codecs and the H.264/AAC
 * out-of-band parameters. Everything else is ignored.
 */
internal data class SdpSession(
    /** `Content-Base` from the RTSP reply, or the request URL plus a slash. */
    val baseUrl: String,
    val tracks: List<SdpTrack>,
)

internal data class SdpTrack(
    val media: String,
    val payloadType: Int,
    /** Lower-case encoding name from `a=rtpmap`, e.g. `h264` or `mpeg4-generic`. */
    val encoding: String,
    val clockRate: Int,
    val channels: Int,
    val control: String?,
    val fmtp: Map<String, String>,
    val spropParameterSets: List<ByteArray>,
) {
    val isVideo: Boolean get() = media.equals("video", ignoreCase = true)
    val isAudio: Boolean get() = media.equals("audio", ignoreCase = true)
    val isH264: Boolean get() = isVideo && encoding.equals("H264", ignoreCase = true)
    val isAac: Boolean
        get() = isAudio && (
            encoding.equals("MPEG4-GENERIC", ignoreCase = true) ||
                encoding.equals("MPEG4GENERIC", ignoreCase = true) ||
                encoding.equals("AAC", ignoreCase = true)
            )

    fun parameterMeaning(key: String): String? = fmtp[key.lowercase()]
}

internal object SdpParser {
    fun parse(sdp: String, baseUrl: String): SdpSession {
        val sessionLines = mutableListOf<String>()
        val mediaSections = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        for (rawLine in sdp.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            when {
                line.startsWith("m=") -> {
                    val section = mutableListOf(line)
                    mediaSections.add(section)
                    current = section
                }

                line.isNotBlank() -> (current ?: sessionLines).add(line)
            }
        }

        val tracks = mediaSections.mapNotNull(::parseTrack)
        return SdpSession(baseUrl = baseUrl, tracks = tracks)
    }

    private fun parseTrack(lines: List<String>): SdpTrack? {
        val mediaParts = lines.firstOrNull()?.removePrefix("m=")?.split(' ') ?: return null
        if (mediaParts.size < 4) return null
        val media = mediaParts[0]
        val payloadType = mediaParts[3].toIntOrNull() ?: return null

        var encoding = ""
        var clockRate = 0
        var channels = 1
        var control: String? = null
        val fmtp = mutableMapOf<String, String>()
        val sprop = mutableListOf<ByteArray>()

        for (line in lines.drop(1)) {
            when {
                line.startsWith("a=rtpmap:") -> {
                    val value = line.removePrefix("a=rtpmap:")
                    val space = value.indexOf(' ')
                    if (space < 0) continue
                    if (value.substring(0, space).toIntOrNull() != payloadType) continue
                    val parts = value.substring(space + 1).split('/')
                    encoding = parts.getOrNull(0).orEmpty()
                    clockRate = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    channels = parts.getOrNull(2)?.toIntOrNull() ?: 1
                }

                line.startsWith("a=fmtp:") -> {
                    val value = line.removePrefix("a=fmtp:")
                    val space = value.indexOf(' ')
                    if (space < 0) continue
                    if (value.substring(0, space).toIntOrNull() != payloadType) continue
                    for (pair in value.substring(space + 1).split(';')) {
                        val equals = pair.indexOf('=')
                        if (equals <= 0) continue
                        fmtp[pair.substring(0, equals).trim().lowercase()] =
                            pair.substring(equals + 1).trim()
                    }
                }

                line.startsWith("a=control:") ->
                    control = line.removePrefix("a=control:").trim()
            }
        }

        fmtp["sprop-parameter-sets"]?.split(',')?.forEach { part ->
            val decoded = decodeBase64(part.trim())
            if (decoded != null) sprop.add(decoded)
        }

        return SdpTrack(
            media = media,
            payloadType = payloadType,
            encoding = encoding,
            clockRate = if (clockRate > 0) clockRate else if (media == "video") 90_000 else 0,
            channels = channels,
            control = control,
            fmtp = fmtp,
            spropParameterSets = sprop,
        )
    }

    private const val BASE64 =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Standard base64 without padding, which is what `sprop-parameter-sets` uses. */
    private fun decodeBase64(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val output = ArrayList<Byte>(text.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (char in text) {
            if (char == '=' || char == '\r' || char == '\n') continue
            val value = BASE64.indexOf(char)
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output.add(((buffer shr bits) and 0xFF).toByte())
            }
        }
        return output.toByteArray()
    }
}
