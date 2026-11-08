package com.cardv.shared.live

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end test of the shared RTSP client against a local, in-process RTSP server.
 *
 * It reproduces what the camera and MediaMTX do on the wire: one TCP connection, interleaved
 * RTP, H.264 with SPS/PPS + FU-A, AAC with RFC 3640 AU headers, and keep-alives.
 */
private fun rtpPacket(
    payload: ByteArray,
    sequence: Int,
    timestamp: Long,
    marker: Boolean,
    payloadType: Int,
): ByteArray {
    val packet = ByteArray(12 + payload.size)
    packet[0] = 0x80.toByte()
    packet[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
    packet[2] = (sequence shr 8).toByte()
    packet[3] = sequence.toByte()
    for (i in 0..3) {
        packet[4 + i] = ((timestamp shr (8 * (3 - i))) and 0xFF).toByte()
    }
    packet[8] = 0x01
    packet[9] = 0x02
    packet[10] = 0x03
    packet[11] = 0x04
    payload.copyInto(packet, 12)
    return packet
}

private fun interleaved(channel: Int, payload: ByteArray): ByteArray {
    val frame = ByteArray(4 + payload.size)
    frame[0] = '$'.code.toByte()
    frame[1] = channel.toByte()
    frame[2] = (payload.size shr 8).toByte()
    frame[3] = payload.size.toByte()
    payload.copyInto(frame, 4)
    return frame
}


class LiveStreamClientTest {
    private class FakeRtspServer(
        val failDescribe: Boolean = false,
        val sendFrames: Boolean = true,
    ) : AutoCloseable {
        private val server = ServerSocket(0)
        val port: Int = server.localPort
        val requests = CopyOnWriteArrayList<String>()
        val teardown = CountDownLatch(1)
        val played = CountDownLatch(1)
        private val writeLock = Any()

        @Volatile
        private var socket: Socket? = null

        @Volatile
        private var finished = false

        private val worker = thread(isDaemon = true, name = "fake-rtsp") {
            runCatching {
                server.accept().use { client ->
                    socket = client
                    val input = BufferedInputStream(client.getInputStream())
                    val output = client.getOutputStream()
                    var playUrl = ""
                    while (true) {
                        val request = readRequest(input) ?: break
                        val firstLine = request.lineSequence().first()
                        val method = firstLine.substringBefore(' ')
                        requests.add(method)
                        val cseq = request.lineSequence()
                            .firstOrNull { it.startsWith("CSeq:", ignoreCase = true) }
                            ?.substringAfter(':')
                            ?.trim()
                            ?: "0"
                        when (method) {
                            "OPTIONS" -> write(output, response(200, cseq, "Public: DESCRIBE, SETUP, PLAY, TEARDOWN"))
                            "DESCRIBE" -> {
                                if (failDescribe) {
                                    write(output, response(405, cseq, "Allow: OPTIONS, DESCRIBE", reason = "Method Not Allowed"))
                                } else {
                                    playUrl = firstLine.split(' ')[1]
                                    write(
                                        output,
                                        response(
                                            200,
                                            cseq,
                                            "Content-Type: application/sdp\r\n" +
                                                "Content-Base: $playUrl/",
                                            sdp(),
                                        ),
                                    )
                                }
                            }

                            "SETUP" -> {
                                val track = firstLine.split(' ')[1]
                                val channel = if (track.endsWith("0")) 0 else 2
                                write(
                                    output,
                                    response(
                                        200,
                                        cseq,
                                        "Session: ABC123;timeout=60\r\n" +
                                            "Transport: RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}",
                                    ),
                                )
                            }

                            "PLAY" -> {
                                write(
                                    output,
                                    response(
                                        200,
                                        cseq,
                                        "Session: ABC123;timeout=60\r\n" +
                                            "RTP-Info: url=$playUrl/streamid=0;seq=1;rtptime=90000",
                                    ),
                                )
                                played.countDown()
                                if (sendFrames) {
                                    thread(isDaemon = true) { sendMedia(output) }
                                }
                            }

                            "TEARDOWN" -> {
                                write(output, response(200, cseq, "Session: ABC123"))
                                finished = true
                                teardown.countDown()
                                return@use
                            }

                            else -> write(output, response(200, cseq, "Public: OPTIONS"))
                        }
                    }
                }
            }
        }

        private fun sendMedia(output: OutputStream) {
            val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
            val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
            write(output, interleaved(0, rtpPacket(sps, 1, 90_000, false, 96)))
            write(output, interleaved(0, rtpPacket(pps, 2, 90_000, false, 96)))
            // An IDR split across two FU-A packets.
            write(output, interleaved(0, rtpPacket(byteArrayOf(0x7C, 0x85.toByte(), 1, 2), 3, 90_000, false, 96)))
            write(output, interleaved(0, rtpPacket(byteArrayOf(0x7C, 0x45, 3, 4), 4, 90_000, true, 96)))
            // P frame.
            write(output, interleaved(0, rtpPacket(byteArrayOf(0x41, 9, 9), 5, 93_000, true, 96)))
            // One AAC frame with an RFC 3640 AU header (size=4).
            write(
                output,
                interleaved(
                    2,
                    rtpPacket(
                        byteArrayOf(0x00, 0x10, 0x00, 0x20, 5, 6, 7, 8),
                        6,
                        32_000,
                        true,
                        97,
                    ),
                ),
            )
            // Keep the stream alive for the duration of the test.
            var sequence = 7
            var timestamp = 96_000L
            while (!finished && socket?.isClosed == false) {
                Thread.sleep(100)
                write(
                    output,
                    interleaved(
                        0,
                        rtpPacket(byteArrayOf(0x41, 0x01), sequence++, timestamp, true, 96),
                    ),
                )
                timestamp += 3_000
            }
        }

        private fun sdp(): String = """
            v=0
            o=- 0 0 IN IP4 127.0.0.1
            s=Fake camera
            c=IN IP4 0.0.0.0
            t=0 0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1; sprop-parameter-sets=Z0IAHw==,aM48gA==
            a=control:streamid=0
            m=audio 0 RTP/AVP 97
            a=rtpmap:97 MPEG4-GENERIC/32000/2
            a=fmtp:97 streamtype=5; profile-level-id=1; mode=AAC-hbr; config=1290; sizelength=13; indexlength=3; indexdeltalength=3
            a=control:streamid=1
        """.trimIndent()

        private fun response(
            status: Int,
            cseq: String,
            headers: String,
            body: String? = null,
            reason: String = "OK",
        ): ByteArray {
            val bodyBytes = body?.encodeToByteArray() ?: ByteArray(0)
            val head = "RTSP/1.0 $status $reason\r\n" +
                "CSeq: $cseq\r\n" +
                "Server: fake-camera\r\n" +
                if (body != null) "Content-Length: ${bodyBytes.size}\r\n" else ""
            return (head + headers + "\r\n\r\n").encodeToByteArray() + bodyBytes
        }

        private fun write(output: OutputStream, bytes: ByteArray) {
            synchronized(writeLock) {
                try {
                    output.write(bytes)
                    output.flush()
                } catch (_: Exception) {
                    // The client closed the socket; the test is finishing.
                }
            }
        }

        private fun readRequest(input: BufferedInputStream): String? {
            val builder = StringBuilder()
            var previous = -1
            while (true) {
                val current = input.read()
                if (current < 0) return if (builder.isEmpty()) null else builder.toString()
                builder.append(current.toChar())
                if (previous == '\r'.code && current == '\n'.code && builder.endsWith("\r\n\r\n")) {
                    return builder.toString()
                }
                previous = current
            }
        }

        override fun close() {
            finished = true
            runCatching { socket?.close() }
            runCatching { server.close() }
            worker.join(2_000)
        }
    }

    private class RecordingSink : LiveSink {
        val states = CopyOnWriteArrayList<Pair<LiveState, String?>>()
        val videos = CopyOnWriteArrayList<LiveVideoSample>()
        val audios = CopyOnWriteArrayList<LiveAudioSample>()
        val stats = CopyOnWriteArrayList<LiveStats>()
        val playing = CountDownLatch(1)
        val twoVideos = CountDownLatch(2)
        val audio = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val failed = CountDownLatch(1)

        override fun onState(state: LiveState, message: String?) {
            states.add(state to message)
            when (state) {
                LiveState.PLAYING -> playing.countDown()
                LiveState.STOPPED -> stopped.countDown()
                LiveState.FAILED -> failed.countDown()
                else -> Unit
            }
        }

        override fun onVideo(sample: LiveVideoSample) {
            videos.add(sample)
            twoVideos.countDown()
        }

        override fun onAudio(sample: LiveAudioSample) {
            audios.add(sample)
            audio.countDown()
            assertEquals(32_000, sample.sampleRate)
            assertEquals(2, sample.channels)
        }

        override fun onStats(stats: LiveStats) {
            this.stats.add(stats)
        }
    }

    @Test(timeout = 30_000)
    fun streamsVideoAudioAndTearsDown() {
        FakeRtspServer().use { server ->
            val sink = RecordingSink()
            val client = LiveStreamClient("rtsp://127.0.0.1:${server.port}/live")

            client.start(sink)
            assertTrue(server.played.await(5, TimeUnit.SECONDS), "the client sent PLAY")
            assertTrue(sink.playing.await(5, TimeUnit.SECONDS), "PLAYING reached")
            assertTrue(sink.twoVideos.await(5, TimeUnit.SECONDS), "two video access units")
            assertTrue(sink.audio.await(5, TimeUnit.SECONDS), "one AAC access unit")

            val first = sink.videos.first()
            assertTrue(first.isKeyFrame)
            assertEquals(0x67, first.sps?.get(0)?.toInt()?.and(0xFF))
            assertEquals(0x68, first.pps?.get(0)?.toInt()?.and(0xFF))
            // AVCC: the first IDR access unit contains SPS, PPS and the reassembled IDR.
            val firstLength = ((first.data[0].toInt() and 0xFF) shl 24) or
                ((first.data[1].toInt() and 0xFF) shl 16) or
                ((first.data[2].toInt() and 0xFF) shl 8) or
                (first.data[3].toInt() and 0xFF)
            assertEquals(4, firstLength)
            // The IDR NAL header follows SPS and PPS, each with a 4-byte length prefix.
            assertEquals(0x65, first.data[20].toInt() and 0xFF)
            assertTrue(sink.audios.first().data.isNotEmpty())

            val setupCount = server.requests.count { it == "SETUP" }
            assertEquals(2, setupCount, "one SETUP per track")

            client.stop()
            assertTrue(server.teardown.await(5, TimeUnit.SECONDS), "TEARDOWN sent")
            assertTrue(sink.stopped.await(5, TimeUnit.SECONDS), "STOPPED reported")
            assertTrue(sink.states.first().first == LiveState.CONNECTING)
        }
    }

    @Test(timeout = 30_000)
    fun keepAliveOptionsArrivesWhilePlaying() {
        FakeRtspServer().use { server ->
            val sink = RecordingSink()
            val client = LiveStreamClient(
                "rtsp://127.0.0.1:${server.port}/live",
                LiveConfig(keepAliveIntervalMillis = 200, statsIntervalMillis = 200),
            )
            client.start(sink)
            assertTrue(server.played.await(5, TimeUnit.SECONDS))
            Thread.sleep(900)
            val options = server.requests.count { it == "OPTIONS" }
            assertTrue(options >= 3, "expected the initial OPTIONS plus keep-alives, saw $options")
            assertTrue(sink.stats.isNotEmpty(), "stats were reported")
            client.stop()
            server.teardown.await(5, TimeUnit.SECONDS)
        }
    }

    @Test(timeout = 30_000)
    fun refusedDescribeFailsWithoutRetrying() {
        FakeRtspServer(failDescribe = true, sendFrames = false).use { server ->
            val sink = RecordingSink()
            val client = LiveStreamClient("rtsp://127.0.0.1:${server.port}/live")
            client.start(sink)
            assertTrue(sink.failed.await(5, TimeUnit.SECONDS), "FAILED reported")
            val failure = sink.states.last { it.first == LiveState.FAILED }
            assertTrue(failure.second?.contains("Method Not Allowed") == true)
            assertEquals(1, server.requests.count { it == "DESCRIBE" })
            client.stop()
        }
    }
}
