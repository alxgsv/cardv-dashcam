package com.cardv.shared.live

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

/**
 * The shared RTSP/RTP-over-TCP live client.
 *
 * This is a direct port of the protocol proven in `tools/probe/ait_probe4.py`: OPTIONS,
 * DESCRIBE, one SETUP per track with `Transport: RTP/AVP/TCP;unicast;interleaved=…`, then PLAY.
 * RTP and RTSP replies arrive interleaved on the same socket. H.264 access units are emitted in
 * AVCC form and AAC access units without their RFC 3640 headers, so the platform layer only has
 * to feed two decoders.
 *
 * Call [start] and [stop] from one thread (the Apple app does so on the main actor). Sink
 * callbacks are delivered on a background thread.
 */
class LiveStreamClient(
    url: String,
    private val config: LiveConfig = LiveConfig(),
    private val socketFactory: LiveSocketFactory = defaultLiveSocketFactory(),
) {
    private val target: RtspUrl?
    private val targetError: String?

    init {
        var parsed: RtspUrl? = null
        var failure: String? = null
        try {
            parsed = RtspUrl.parse(url)
        } catch (error: LiveException) {
            failure = error.message
        }
        target = parsed
        targetError = failure
    }

    private var job: Job? = null

    @Volatile
    private var running = false

    @Volatile
    private var generation = 0

    @Volatile
    private var activeSocket: LiveSocket? = null

    fun start(sink: LiveSink) {
        if (running) return
        val target = target
        if (target == null) {
            sink.onState(LiveState.FAILED, targetError ?: "The live address is not valid.")
            return
        }
        running = true
        generation += 1
        val sessionGeneration = generation
        val session = RtspSession(
            url = target,
            config = config,
            sink = sink,
            socketFactory = socketFactory,
            isRunning = { running && generation == sessionGeneration },
            onSocket = { if (generation == sessionGeneration) activeSocket = it },
        )
        job = CoroutineScope(Dispatchers.Default).launch {
            try {
                session.run()
            } finally {
                activeSocket = null
            }
        }
    }

    /** Returns immediately; the reader sends TEARDOWN and closes the socket on its next poll. */
    fun stop() {
        if (!running) return
        running = false
        generation += 1
        val stopGeneration = generation
        val current = job
        job = null
        if (current == null) {
            activeSocket?.close()
            activeSocket = null
            return
        }
        // A connect can outlive the short read timeout; close it if the session has not finished.
        // The generation check keeps a quick stop-then-start from being closed by the old watchdog.
        CoroutineScope(Dispatchers.Default).launch {
            delay(1_500)
            if (current.isActive && generation == stopGeneration) {
                runCatching { activeSocket?.close() }
                current.cancel()
            }
        }
    }
}

private class RtspSession(
    private val url: RtspUrl,
    private val config: LiveConfig,
    private val sink: LiveSink,
    private val socketFactory: LiveSocketFactory,
    private val isRunning: () -> Boolean,
    private val onSocket: (LiveSocket?) -> Unit,
) {
    private val started = TimeSource.Monotonic.markNow()
    private var socket: LiveSocket? = null
    private lateinit var reader: RtspReader
    private var cseq = 0
    private var sessionId: String? = null
    private var sessionTimeoutMillis = config.defaultSessionTimeoutMillis
    private var contentBase = url.requestUrl.trimEnd('/') + "/"

    private val keepAliveMicros: Long
        get() = minOf(config.keepAliveIntervalMillis, sessionTimeoutMillis / 2) * 1_000

    fun run() {
        var reportedTerminal = false
        try {
            sink.onState(LiveState.CONNECTING, null)
            val connection = try {
                socketFactory.connect(
                    host = url.host,
                    port = url.port,
                    connectTimeoutMillis = config.connectTimeoutMillis,
                    readTimeoutMillis = config.readTimeoutMillis,
                )
            } catch (error: Throwable) {
                throw LiveException(
                    "Can't open the live stream at ${url.host}:${url.port} " +
                        "(${error.message ?: "connection failed"}). Stay on the camera's Wi-Fi and try again.",
                    error,
                )
            }
            socket = connection
            onSocket(connection)
            if (!isRunning()) return
            reader = RtspReader(connection)

            val video = handshake() ?: return
            if (!isRunning()) return
            stream(video)
        } catch (error: LiveException) {
            if (isRunning()) {
                reportedTerminal = true
                sink.onState(LiveState.FAILED, error.message ?: "The live stream failed.")
            }
        } catch (error: Throwable) {
            if (isRunning()) {
                reportedTerminal = true
                sink.onState(
                    LiveState.FAILED,
                    error.message ?: "The live stream failed unexpectedly.",
                )
            }
        } finally {
            if (sessionId != null) runCatching { sendOnly("TEARDOWN", contentBase) }
            runCatching { socket?.close() }
            socket = null
            onSocket(null)
            if (!reportedTerminal) sink.onState(LiveState.STOPPED, null)
        }
    }

    // MARK: handshake

    private fun handshake(): VideoTrackSetup? {
        val options = request("OPTIONS", url.requestUrl)
        if (options.statusCode == 401 || options.statusCode == 403) {
            throw LiveException("The camera refused the live stream (${options.statusCode}).")
        }

        val describe = request("DESCRIBE", url.requestUrl, listOf("Accept: application/sdp"))
        if (!describe.isSuccess) throw LiveException(responseMessage("describe", describe))
        if (describe.body.isBlank()) throw LiveException("The camera sent no stream description.")

        val base = describe.header("content-base")?.trim().orEmpty()
        if (base.isNotEmpty()) {
            contentBase = if (base.endsWith('/')) base else "$base/"
        }

        val sdp = SdpParser.parse(describe.body, contentBase)
        val video = sdp.tracks.firstOrNull { it.isH264 }
            ?: throw LiveException("The live stream has no H.264 video.")
        val audio = sdp.tracks.firstOrNull { it.isAac && it.channels > 0 }

        val videoSetup = setup(video, firstChannel = 0)
        val audioSetup = audio?.let { setup(it, firstChannel = 2) }

        val play = request("PLAY", contentBase, listOf("Range: npt=0.000-"))
        if (!play.isSuccess) throw LiveException(responseMessage("start", play))

        return VideoTrackSetup(
            setup = videoSetup,
            audio = audioSetup,
            audioConfig = audio?.let(::audioConfiguration),
        )
    }

    private fun setup(track: SdpTrack, firstChannel: Int): TrackSetup {
        val trackUrl = resolveControl(track.control)
        val response = request(
            "SETUP",
            trackUrl,
            listOf("Transport: RTP/AVP/TCP;unicast;interleaved=$firstChannel-${firstChannel + 1}"),
        )
        if (!response.isSuccess) throw LiveException(responseMessage("set up", response))

        val transport = response.header("transport").orEmpty()
        if (transport.contains("client_port", ignoreCase = true) &&
            !transport.contains("interleaved", ignoreCase = true)
        ) {
            throw LiveException("The camera refused RTP over TCP.")
        }
        val interleaved = Regex("interleaved=(\\d+)-(\\d+)")
            .find(transport)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        return TrackSetup(
            track = track,
            url = trackUrl,
            firstChannel = interleaved ?: firstChannel,
        )
    }

    private fun resolveControl(control: String?): String = when {
        control.isNullOrBlank() || control == "*" -> contentBase
        control.startsWith("rtsp://", ignoreCase = true) -> control
        else -> contentBase.trimEnd('/') + "/" + control.trimStart('/')
    }

    private fun audioConfiguration(track: SdpTrack): AacConfiguration {
        val sdpConfig = track.parameterMeaning("config")
            ?.trim()
            ?.let(::hexToBytes)
            ?.takeIf { it.isNotEmpty() }
        val fromConfig = sdpConfig?.let(::audioSpecificConfigValues)
        val sampleRate = fromConfig?.first?.takeIf { it > 0 }
            ?: track.clockRate.takeIf { it > 0 }
            ?: config.fallbackAudioSampleRate
        val channels = fromConfig?.second?.takeIf { it > 0 }
            ?: track.channels.takeIf { it > 0 }
            ?: config.fallbackAudioChannels
        val asc = sdpConfig ?: AacDepacketizer.audioSpecificConfig(sampleRate, channels)
        val mode = track.parameterMeaning("mode").orEmpty()
        return AacConfiguration(
            sampleRate = sampleRate,
            channels = channels,
            audioSpecificConfig = asc,
            sizeLength = track.parameterMeaning("sizelength")?.toIntOrNull() ?: 13,
            indexLength = track.parameterMeaning("indexlength")?.toIntOrNull() ?: 3,
            indexDeltaLength = track.parameterMeaning("indexdeltalength")?.toIntOrNull() ?: 3,
            // RFC 3640 defaults MPEG4-GENERIC to AAC-hbr, and both AAC modes carry AU headers.
            hasAuHeaders = mode.isBlank() ||
                mode.contains("AAC-", ignoreCase = true) ||
                mode.contains("generic", ignoreCase = true),
        )
    }

    // MARK: streaming

    private fun stream(setup: VideoTrackSetup) {
        val video = VideoTrackState(
            setup = setup.setup,
            depacketizer = H264Depacketizer().apply {
                setup.setup.track.spropParameterSets.getOrNull(0)?.let(::seedSps)
                setup.setup.track.spropParameterSets.getOrNull(1)?.let(::seedPps)
            },
        )
        val audio = setup.audio?.let { audioSetup ->
            val config = setup.audioConfig ?: return@let null
            AudioTrackState(
                setup = audioSetup,
                sampleRate = config.sampleRate,
                channels = config.channels,
                depacketizer = AacDepacketizer(
                    sampleRate = config.sampleRate,
                    channels = config.channels,
                    audioSpecificConfig = config.audioSpecificConfig,
                    sizeLength = config.sizeLength,
                    indexLength = config.indexLength,
                    indexDeltaLength = config.indexDeltaLength,
                    hasAuHeaders = config.hasAuHeaders,
                ),
            )
        }
        val byChannel = buildMap {
            put(video.setup.firstChannel, video)
            if (audio != null) put(audio.setup.firstChannel, audio)
        }

        var emittedFirstVideo = false
        var announcedPlaying = false
        var lastVideoPacketMicros = elapsedMicros()
        var lastStatsMicros = lastVideoPacketMicros
        var lastControlMicros = lastVideoPacketMicros
        var lastVideoFrames = 0L
        var lastAudioFrames = 0L
        var lastVideoBytes = 0L
        var lastAudioBytes = 0L
        var firstFrameMillis: Long? = null

        fun emitStats(now: Long) {
            val interval = now - lastStatsMicros
            if (interval < config.statsIntervalMillis * 1_000) return
            val videoFrames = video.frames
            val audioFrames = audio?.frames ?: 0
            val videoBytes = video.bytes
            val audioBytes = audio?.bytes ?: 0
            val seconds = interval / 1_000_000.0
            sink.onStats(
                LiveStats(
                    elapsedMillis = now / 1_000,
                    videoFrames = videoFrames,
                    audioFrames = audioFrames,
                    videoBytes = videoBytes,
                    audioBytes = audioBytes,
                    lostRtpPackets = video.lostPackets + (audio?.lostPackets ?: 0),
                    videoFps = if (seconds > 0) (videoFrames - lastVideoFrames) / seconds else 0.0,
                    videoKbitPerSecond = if (seconds > 0) {
                        (videoBytes - lastVideoBytes) * 8.0 / seconds / 1_000
                    } else {
                        0.0
                    },
                    audioKbitPerSecond = if (seconds > 0) {
                        (audioBytes - lastAudioBytes) * 8.0 / seconds / 1_000
                    } else {
                        0.0
                    },
                    firstFrameMillis = firstFrameMillis,
                ),
            )
            lastStatsMicros = now
            lastVideoFrames = videoFrames
            lastAudioFrames = audioFrames
            lastVideoBytes = videoBytes
            lastAudioBytes = audioBytes
        }

        while (isRunning()) {
            val now = elapsedMicros()
            if (now - lastControlMicros >= keepAliveMicros) {
                sendOnly("OPTIONS", contentBase)
                lastControlMicros = now
            }
            if (now - lastVideoPacketMicros >= config.stallTimeoutMillis * 1_000) {
                throw LiveException("The camera stopped sending video.")
            }

            when (val incoming = reader.next()) {
                null -> Unit

                is Incoming.Response -> {
                    // A keep-alive reply; nothing to do.
                }

                is Incoming.Frame -> {
                    val track = byChannel[incoming.channel] ?: continue
                    val packet = RtpPacket.parse(incoming.payload, 0, incoming.payload.size) ?: continue
                    track.noteSequence(packet.sequence)
                    if (track === video) lastVideoPacketMicros = elapsedMicros()

                    when (track) {
                        is VideoTrackState -> {
                            val accessUnit = track.depacketizer.push(
                                payload = incoming.payload,
                                offset = packet.payloadOffset,
                                length = packet.payloadLength,
                                timestamp = packet.timestamp,
                                marker = packet.marker,
                            )
                            if (accessUnit != null) {
                                track.frames++
                                if (!track.seenKeyFrame) {
                                    if (!accessUnit.isKeyFrame) continue
                                    track.seenKeyFrame = true
                                }
                                val data = accessUnit.toAvcc()
                                track.bytes += data.size
                                val sample = LiveVideoSample(
                                    data = data,
                                    presentationTimeMicros = track.presentationTimeMicros(
                                        packet.timestamp,
                                        elapsedMicros(),
                                    ),
                                    isKeyFrame = accessUnit.isKeyFrame,
                                    sps = track.depacketizer.sps,
                                    pps = track.depacketizer.pps,
                                )
                                if (!emittedFirstVideo) {
                                    emittedFirstVideo = true
                                    firstFrameMillis = elapsedMicros() / 1_000
                                }
                                sink.onVideo(sample)
                            }
                        }

                        is AudioTrackState -> {
                            val frames = track.depacketizer.push(
                                payload = incoming.payload,
                                offset = packet.payloadOffset,
                                length = packet.payloadLength,
                                timestamp = packet.timestamp,
                            )
                            for ((index, frame) in frames.withIndex()) {
                                track.frames++
                                track.bytes += frame.size
                                sink.onAudio(
                                    LiveAudioSample(
                                        data = frame,
                                        presentationTimeMicros = track.presentationTimeMicros(
                                            packet.timestamp,
                                            elapsedMicros(),
                                            index,
                                        ),
                                        sampleRate = track.sampleRate,
                                        channels = track.channels,
                                        audioSpecificConfig = track.depacketizer.audioSpecificConfig,
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            if (emittedFirstVideo && !announcedPlaying) {
                announcedPlaying = true
                sink.onState(LiveState.PLAYING, null)
            }
            emitStats(elapsedMicros())
        }
    }

    // MARK: RTSP plumbing

    private fun request(
        method: String,
        uri: String,
        extraHeaders: List<String> = emptyList(),
        body: String? = null,
        timeoutMillis: Int = config.connectTimeoutMillis,
    ): RtspResponse {
        sendOnly(method, uri, extraHeaders, body)
        return awaitResponse(timeoutMillis)
    }

    private fun sendOnly(
        method: String,
        uri: String,
        extraHeaders: List<String> = emptyList(),
        body: String? = null,
    ) {
        val connection = socket ?: throw LiveException("The live connection is closed.")
        cseq++
        val message = buildRtspRequest(
            method = method,
            url = uri,
            cseq = cseq,
            session = sessionId,
            userAgent = config.userAgent,
            extraHeaders = extraHeaders,
            body = body,
        )
        try {
            connection.write(message, 0, message.size)
        } catch (error: Throwable) {
            throw LiveException("The live connection was lost.", error)
        }
    }

    private fun awaitResponse(timeoutMillis: Int): RtspResponse {
        val deadline = elapsedMicros() + timeoutMillis * 1_000L
        while (true) {
            if (!isRunning()) throw LiveException("Live view stopped.")
            when (val incoming = reader.next()) {
                is Incoming.Response -> {
                    noteSession(incoming.response)
                    return incoming.response
                }

                // Frames cannot precede PLAY in this protocol; ignore them if they do.
                is Incoming.Frame -> Unit
                null -> Unit
            }
            if (elapsedMicros() > deadline) {
                throw LiveException("The camera did not answer the live request in time.")
            }
        }
    }

    private fun noteSession(response: RtspResponse) {
        val value = response.header("session") ?: return
        val id = value.substringBefore(';').trim()
        if (id.isNotEmpty()) sessionId = id
        val timeout = Regex("timeout=(\\d+)")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        if (timeout != null && timeout > 0) sessionTimeoutMillis = timeout * 1_000
    }

    private fun elapsedMicros(): Long = started.elapsedNow().inWholeMicroseconds

    private fun responseMessage(action: String, response: RtspResponse): String {
        val detail = response.reason.ifBlank { "error ${response.statusCode}" }
        return "The camera could not $action the live stream ($detail)."
    }
}

// MARK: - per-track state

private data class TrackSetup(
    val track: SdpTrack,
    val url: String,
    val firstChannel: Int,
)

private data class VideoTrackSetup(
    val setup: TrackSetup,
    val audio: TrackSetup?,
    val audioConfig: AacConfiguration?,
)

private data class AacConfiguration(
    val sampleRate: Int,
    val channels: Int,
    val audioSpecificConfig: ByteArray,
    val sizeLength: Int,
    val indexLength: Int,
    val indexDeltaLength: Int,
    val hasAuHeaders: Boolean,
)

private sealed class TrackState(val setup: TrackSetup) {
    var lastSequence = -1
    var lostPackets = 0L
    var frames = 0L
    var bytes = 0L

    fun noteSequence(sequence: Int) {
        if (lastSequence >= 0) {
            val expected = (lastSequence + 1) and 0xFFFF
            if (sequence != expected) {
                val delta = (sequence - lastSequence - 1) and 0xFFFF
                if (delta in 1..3_000) lostPackets += delta
            }
        }
        lastSequence = sequence
    }
}

private class VideoTrackState(
    setup: TrackSetup,
    val depacketizer: H264Depacketizer,
) : TrackState(setup) {
    var seenKeyFrame = false
    private var firstTimestamp: Long? = null
    private var firstArrivalMicros = 0L

    fun presentationTimeMicros(timestamp: Long, arrivalMicros: Long): Long {
        val base = firstTimestamp
        if (base == null) {
            firstTimestamp = timestamp
            firstArrivalMicros = arrivalMicros
            return firstArrivalMicros
        }
        val delta = (timestamp - base) and 0xFFFFFFFFL
        return firstArrivalMicros + delta * 1_000_000L / 90_000L
    }
}

private class AudioTrackState(
    setup: TrackSetup,
    val sampleRate: Int,
    val channels: Int,
    val depacketizer: AacDepacketizer,
) : TrackState(setup) {
    private var firstTimestamp: Long? = null
    private var firstArrivalMicros = 0L

    fun presentationTimeMicros(timestamp: Long, arrivalMicros: Long, index: Int): Long {
        val base = firstTimestamp
        if (base == null) {
            firstTimestamp = timestamp
            firstArrivalMicros = arrivalMicros
        }
        return depacketizer.presentationTimeMicros(
            timestamp = timestamp,
            baseTimestamp = firstTimestamp ?: timestamp,
            baseMicros = firstArrivalMicros,
            index = index,
        )
    }
}

// MARK: - small helpers

internal fun AccessUnit.toAvcc(): ByteArray {
    var size = 0
    for (nal in nalUnits) size += 4 + nal.size
    val output = ByteArray(size)
    var offset = 0
    for (nal in nalUnits) {
        output[offset] = ((nal.size shr 24) and 0xFF).toByte()
        output[offset + 1] = ((nal.size shr 16) and 0xFF).toByte()
        output[offset + 2] = ((nal.size shr 8) and 0xFF).toByte()
        output[offset + 3] = (nal.size and 0xFF).toByte()
        offset += 4
        nal.copyInto(output, offset)
        offset += nal.size
    }
    return output
}

private fun H264Depacketizer.seedSps(value: ByteArray) {
    sps = value
}

private fun H264Depacketizer.seedPps(value: ByteArray) {
    pps = value
}

private fun hexToBytes(text: String): ByteArray? {
    val cleaned = text.trim()
    if (cleaned.length % 2 != 0) return null
    val result = ByteArray(cleaned.length / 2)
    for (i in result.indices) {
        val hi = cleaned[i * 2].digitToIntOrNull(16) ?: return null
        val lo = cleaned[i * 2 + 1].digitToIntOrNull(16) ?: return null
        if (hi < 0 || lo < 0) return null
        result[i] = ((hi shl 4) or lo).toByte()
    }
    return result
}

/**
 * Reads the sampling frequency index and channel configuration out of an AudioSpecificConfig.
 * `null` when the config uses an explicit frequency or a non-AAC-LC object type, in which case
 * the SDP clock rate and channel count are used instead.
 */
private fun audioSpecificConfigValues(config: ByteArray): Pair<Int, Int>? {
    if (config.size < 2) return null
    val bits = ((config[0].toInt() and 0xFF) shl 8) or (config[1].toInt() and 0xFF)
    val objectType = (bits shr 11) and 0x1F
    if (objectType != 2 && objectType != 5) return null
    val frequencyIndex = (bits shr 7) and 0x0F
    val rates = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000, 7350,
    )
    val rate = rates.getOrNull(frequencyIndex) ?: return null
    val channels = (bits shr 3) and 0x0F
    return rate to channels
}
