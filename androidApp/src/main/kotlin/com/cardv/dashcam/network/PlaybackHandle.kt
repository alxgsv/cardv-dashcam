package com.cardv.dashcam.network

import com.cardv.shared.model.Clip
import okhttp3.OkHttpClient
import javax.net.SocketFactory

/** Everything a player or photo viewer needs for one clip, bound to the camera network. */
data class PlaybackHandle(
    val clip: Clip,
    val url: String,
    val okHttpClient: OkHttpClient,
)

/**
 * One live RTSP stream.
 *
 * [socketFactory] is the camera Wi-Fi network's factory when the app is bound to the real camera
 * (`192.168.42.x`). It makes Media3's RTSP client use the camera network even if the process
 * binding is lost; debug/LAN overrides pass `null` and use the normal route.
 */
data class LiveHandle(
    val url: String,
    val socketFactory: SocketFactory?,
)
