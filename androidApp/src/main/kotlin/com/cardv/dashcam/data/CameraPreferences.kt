package com.cardv.dashcam.data

import android.content.Context
import androidx.core.content.edit
import com.cardv.dashcam.BuildConfig
import com.cardv.dashcam.network.CameraAddress
import com.cardv.dashcam.network.LiveAddress
import com.cardv.shared.CameraClient

/**
 * Camera address settings.
 *
 * Release builds always use the documented camera address. Debug builds can point at the fake
 * camera (`http://10.0.2.2:8080` on the emulator) or a device on the LAN, which is how the M2
 * app is exercised without a car.
 */
class CameraPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    val defaultBaseUrl: String = CameraClient.DEFAULT_BASE_URL

    fun baseUrl(): String {
        if (!BuildConfig.DEBUG) return defaultBaseUrl
        val stored = prefs.getString(KEY_BASE_URL, null) ?: return defaultBaseUrl
        return CameraAddress.normalize(stored) ?: defaultBaseUrl
    }

    val hasDebugOverride: Boolean
        get() = baseUrl() != defaultBaseUrl

    /** Ignored in release builds; returns the value that will actually be used. */
    fun setBaseUrl(raw: String?): String {
        if (!BuildConfig.DEBUG) return defaultBaseUrl
        val normalized = raw?.let(CameraAddress::normalize)
        if (normalized == null) {
            prefs.edit { remove(KEY_BASE_URL) }
        } else {
            prefs.edit { putString(KEY_BASE_URL, normalized) }
        }
        return baseUrl()
    }

    /** Debug-only live RTSP override; `null` means the shared camera default. */
    fun liveUrlOverride(): String? {
        if (!BuildConfig.DEBUG) return null
        return LiveAddress.normalize(prefs.getString(KEY_LIVE_URL, null))
    }

    /** Debug-only; ignored in release builds. */
    fun setLiveUrlOverride(raw: String?): String? {
        if (!BuildConfig.DEBUG) return null
        val normalized = raw?.let(LiveAddress::normalize)
        if (normalized == null) {
            prefs.edit { remove(KEY_LIVE_URL) }
        } else {
            prefs.edit { putString(KEY_LIVE_URL, normalized) }
        }
        return normalized
    }

    /** Explicit opt-in for EXPERIMENTAL settings; OFF by default on every install. */
    fun experimentalEnabled(): Boolean = prefs.getBoolean(KEY_EXPERIMENTAL, false)

    fun setExperimentalEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_EXPERIMENTAL, enabled) }
    }

    fun reset() {
        if (BuildConfig.DEBUG) {
            prefs.edit {
                remove(KEY_BASE_URL)
                remove(KEY_LIVE_URL)
            }
        }
    }

    private companion object {
        const val NAME = "camera"
        const val KEY_BASE_URL = "base_url_override"
        const val KEY_LIVE_URL = "live_url_override"
        const val KEY_EXPERIMENTAL = "settings_experimental"
    }
}
