package com.cardv.shared.apple

import com.cardv.shared.live.LiveAudioSample
import com.cardv.shared.live.LiveConfig
import com.cardv.shared.live.LiveSink
import com.cardv.shared.live.LiveStats
import com.cardv.shared.live.LiveState
import com.cardv.shared.live.LiveStreamClient
import com.cardv.shared.live.LiveVideoSample
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.create

/** One H.264 access unit for AVFoundation, with the bytes already bridgeable to `Data`. */
data class AppleLiveVideoSample(
    val data: NSData,
    val presentationTimeMicros: Long,
    val isKeyFrame: Boolean,
    val sps: NSData?,
    val pps: NSData?,
)

/** One AAC access unit plus the AudioSpecificConfig needed to describe it to Core Audio. */
data class AppleLiveAudioSample(
    val data: NSData,
    val presentationTimeMicros: Long,
    val sampleRate: Int,
    val channels: Int,
    val audioSpecificConfig: NSData,
)

/** Swift-facing live sink. Called from a Kotlin background thread, never the main thread. */
interface AppleLiveSink {
    fun onState(state: LiveState, message: String?)

    fun onVideo(sample: AppleLiveVideoSample)

    fun onAudio(sample: AppleLiveAudioSample)

    fun onStats(stats: LiveStats)
}

/**
 * Wraps the shared RTSP client so Swift never deals with Kotlin byte arrays.
 *
 * The RTSP/SDP/RTP work lives in `commonMain`; this facade only converts access units to `NSData`
 * and keeps the adapter alive for the lifetime of the session.
 */
class AppleLiveStream internal constructor(
    url: String,
    sink: AppleLiveSink,
    config: LiveConfig,
) {
    private val adapter = Adapter(sink)
    private val client = LiveStreamClient(url, config)

    fun start() {
        client.start(adapter)
    }

    fun stop() {
        client.stop()
    }
}

object AppleLive {
    /** Builds a live client with the shared defaults (Swift cannot omit Kotlin default arguments). */
    fun stream(url: String, sink: AppleLiveSink): AppleLiveStream =
        AppleLiveStream(url = url, sink = sink, config = LiveConfig())

    /** Same, with a shorter stall timeout, for the debug spike harness. */
    fun streamWithConfig(url: String, sink: AppleLiveSink, config: LiveConfig): AppleLiveStream =
        AppleLiveStream(url = url, sink = sink, config = config)
}

private class Adapter(private val sink: AppleLiveSink) : LiveSink {
    override fun onState(state: LiveState, message: String?) {
        sink.onState(state, message)
    }

    override fun onVideo(sample: LiveVideoSample) {
        sink.onVideo(
            AppleLiveVideoSample(
                data = sample.data.toNSData(),
                presentationTimeMicros = sample.presentationTimeMicros,
                isKeyFrame = sample.isKeyFrame,
                sps = sample.sps?.toNSData(),
                pps = sample.pps?.toNSData(),
            ),
        )
    }

    override fun onAudio(sample: LiveAudioSample) {
        sink.onAudio(
            AppleLiveAudioSample(
                data = sample.data.toNSData(),
                presentationTimeMicros = sample.presentationTimeMicros,
                sampleRate = sample.sampleRate,
                channels = sample.channels,
                audioSpecificConfig = sample.audioSpecificConfig.toNSData(),
            ),
        )
    }

    override fun onStats(stats: LiveStats) {
        sink.onStats(stats)
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
