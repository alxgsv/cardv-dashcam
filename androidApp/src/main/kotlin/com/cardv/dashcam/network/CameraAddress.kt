package com.cardv.dashcam.network

import com.cardv.shared.CameraClient
import java.net.URI

/** Pure helpers for camera addresses, shared by the UI and unit tests. */
object CameraAddress {
    /** Address from docs/PROTOCOL.md; only this host gets the camera Wi-Fi binding. */
    const val CAMERA_HOST: String = "192.168.42.1"

    /**
     * Normalize a user-entered base URL: trim, add `http://` when no scheme is present, drop a
     * trailing slash. Returns `null` for blank or host-less input.
     */
    fun normalize(raw: String?): String? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        if (!text.startsWith("http://", ignoreCase = true) &&
            !text.startsWith("https://", ignoreCase = true)
        ) {
            text = "http://$text"
        }
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return text.trimEnd('/')
    }

    fun host(baseUrl: String): String? =
        runCatching { URI(baseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * True only for the real camera address. A debug/LAN override must not make the app bind
     * itself to the phone's ordinary Wi-Fi.
     */
    fun isCameraHost(baseUrl: String): Boolean = host(baseUrl) == CAMERA_HOST

    fun defaultBaseUrl(): String = CameraClient.DEFAULT_BASE_URL
}
