package com.cardv.dashcam.network

import com.cardv.shared.CameraClient
import com.cardv.shared.model.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Media reads from the camera: file ranges and whole photos.
 *
 * These are file reads, not `Config.cgi` commands, so they intentionally do not go through the
 * shared `CommandQueue` (docs/PROTOCOL.md: file reads may overlap with commands and downloads).
 */
class CameraMedia(
    private val client: CameraClient,
    private val http: OkHttpClient,
) {
    fun clipUrl(clip: Clip): String = client.clipUrl(clip)

    /**
     * Video thumbnails come from the MP4 `skip` box through [CameraClient.thumbnail]. Photos are
     * ordinary JPEGs with no such box, so fetch the (small) file and let the caller downsample.
     */
    suspend fun thumbnail(clip: Clip): ByteArray? =
        if (clip.isVideo) client.thumbnail(clip) else photoBytes(clip)

    suspend fun photoBytes(clip: Clip): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(client.clipUrl(clip))
            .header("Accept", "image/jpeg")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            if (body.contentLength() > MAX_PHOTO_BYTES) return@use null
            body.bytes()
        }
    }

    private companion object {
        const val MAX_PHOTO_BYTES = 8L * 1024 * 1024
    }
}

/** Everything one connected camera session owns. [close] releases both HTTP clients and binding. */
class CameraSession(
    val baseUrl: String,
    val client: CameraClient,
    val lease: CameraNetworkLease,
    val media: CameraMedia,
) : AutoCloseable {
    val okHttpClient: OkHttpClient get() = lease.okHttpClient()

    override fun close() {
        client.close()
        lease.close()
    }
}
