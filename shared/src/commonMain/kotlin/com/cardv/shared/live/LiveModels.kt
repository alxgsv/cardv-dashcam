package com.cardv.shared.live

/**
 * Live view states, mirroring what the Apple UI shows.
 *
 * [PLAYING] means video is actually arriving: the session waits for the first decodable
 * access unit, not for the RTSP `PLAY` reply, so a camera that accepts the request but sends
 * nothing still ends up in [FAILED] after [LiveConfig.stallTimeoutMillis].
 */
enum class LiveState {
    IDLE,
    CONNECTING,
    PLAYING,
    STOPPED,
    FAILED,
}

/** One H.264 access unit, already depacketised and in AVCC form (4-byte length prefixes). */
data class LiveVideoSample(
    val data: ByteArray,
    /** Presentation time relative to the start of this live session, in microseconds. */
    val presentationTimeMicros: Long,
    val isKeyFrame: Boolean,
    /** Sequence parameter set, when known. Swift rebuilds the format description if it changes. */
    val sps: ByteArray?,
    /** Picture parameter set, when known. */
    val pps: ByteArray?,
)

/**
 * One AAC access unit without its RTP payload header.
 *
 * [audioSpecificConfig] is the AudioSpecificConfig from the SDP `config=` field. When the camera
 * leaves it out, the client synthesises an AAC-LC config from the RTP clock rate and channels.
 */
data class LiveAudioSample(
    val data: ByteArray,
    val presentationTimeMicros: Long,
    val sampleRate: Int,
    val channels: Int,
    val audioSpecificConfig: ByteArray,
)

/** Periodic counters for the UI and for "is the stream healthy" checks. */
data class LiveStats(
    val elapsedMillis: Long,
    val videoFrames: Long,
    val audioFrames: Long,
    val videoBytes: Long,
    val audioBytes: Long,
    val lostRtpPackets: Long,
    val videoFps: Double,
    val videoKbitPerSecond: Double,
    val audioKbitPerSecond: Double,
    /** Time from `start()` to the first video access unit; `null` until it arrives. */
    val firstFrameMillis: Long?,
)

/**
 * Receives a live session. All methods are called from the client's reader thread (never the
 * main thread), so a UI must hop to its own main queue.
 */
interface LiveSink {
    fun onState(state: LiveState, message: String?)

    fun onVideo(sample: LiveVideoSample)

    fun onAudio(sample: LiveAudioSample)

    fun onStats(stats: LiveStats)
}

data class LiveConfig(
    val connectTimeoutMillis: Int = 8_000,
    /** Read poll interval. Short enough that Stop and keep-alives are responsive. */
    val readTimeoutMillis: Int = 250,
    /** Used only when SETUP does not return a `timeout=` parameter. */
    val defaultSessionTimeoutMillis: Long = 60_000,
    /** A live session that sends no video for this long is failed, not retried. */
    val stallTimeoutMillis: Long = 12_000,
    /** Send `OPTIONS` this often; capped at half the session timeout. */
    val keepAliveIntervalMillis: Long = 20_000,
    val statsIntervalMillis: Long = 1_000,
    val userAgent: String = "CarDV/1.0",
    /** The camera's `av2` stream is AAC-LC 32 kHz stereo; SDP usually states it too. */
    val fallbackAudioSampleRate: Int = 32_000,
    val fallbackAudioChannels: Int = 2,
) {
    init {
        require(connectTimeoutMillis > 0)
        require(readTimeoutMillis > 0)
        require(stallTimeoutMillis > 0)
        require(keepAliveIntervalMillis > 0)
        require(statsIntervalMillis > 0)
    }
}

/** A live view problem that should be shown to the user instead of being retried. */
class LiveException(message: String, cause: Throwable? = null) : Exception(message, cause)
