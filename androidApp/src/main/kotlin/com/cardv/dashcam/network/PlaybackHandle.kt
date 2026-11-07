package com.cardv.dashcam.network

import com.cardv.shared.model.Clip
import okhttp3.OkHttpClient

/** Everything a player or photo viewer needs for one clip, bound to the camera network. */
data class PlaybackHandle(
    val clip: Clip,
    val url: String,
    val okHttpClient: OkHttpClient,
)
