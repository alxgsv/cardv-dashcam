package com.cardv.dashcam.network

import java.net.URI

/**
 * Parsing for the debug-only live RTSP address.
 *
 * The real app always uses `rtsp://<camera-host>:554/liveRTSP/av2`, built by the shared
 * `CameraClient.liveUrl()`. A debug build may point at a local test stream (for example MediaMTX on
 * `rtsp://192.168.1.20:8554/live`) so live view can be exercised without a car.
 */
object LiveAddress {
    const val DEFAULT_PORT: Int = 554
    const val DEFAULT_PATH: String = "liveRTSP/av2"

    /** Normalize `host[:port][/path]` or a full `rtsp://` URL; `null` for unusable input. */
    fun normalize(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val hasScheme = text.contains("://")
        if (hasScheme && !text.startsWith("rtsp://", ignoreCase = true)) return null
        if (!hasScheme) text = "rtsp://$text"

        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("rtsp", ignoreCase = true)) return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it > 0 } ?: DEFAULT_PORT
        val path = uri.path?.takeIf { it.isNotBlank() && it != "/" } ?: "/$DEFAULT_PATH"
        val normalizedPath = if (path.startsWith('/')) path else "/$path"
        return "rtsp://$host:$port$normalizedPath"
    }
}
