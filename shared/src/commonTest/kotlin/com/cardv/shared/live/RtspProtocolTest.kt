package com.cardv.shared.live

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class RtspProtocolTest {
    private class ScriptedSocket(vararg chunks: ByteArray) : LiveSocket {
        private val pending = ArrayDeque(chunks.toList())
        private var ended = false
        val writes = mutableListOf<ByteArray>()

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (ended) return -1
            val chunk = pending.removeFirstOrNull() ?: return 0
            if (chunk.isEmpty()) return -1
            val count = minOf(chunk.size, length)
            chunk.copyInto(buffer, offset, 0, count)
            if (count < chunk.size) pending.addFirst(chunk.copyOfRange(count, chunk.size))
            return count
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writes.add(buffer.copyOfRange(offset, offset + length))
        }

        override fun close() {
            ended = true
        }
    }

    private fun frame(channel: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(4 + payload.size)
        packet[0] = '$'.code.toByte()
        packet[1] = channel.toByte()
        packet[2] = (payload.size shr 8).toByte()
        packet[3] = payload.size.toByte()
        payload.copyInto(packet, 4)
        return packet
    }

    @Test
    fun requestFormatMatchesTheCameraProtocol() {
        val request = buildRtspRequest(
            method = "SETUP",
            url = "rtsp://192.168.42.1:554/liveRTSP/av2/track0",
            cseq = 3,
            session = "ABC123",
            userAgent = "CarDV/1.0",
            extraHeaders = listOf("Transport: RTP/AVP/TCP;unicast;interleaved=0-1"),
        ).decodeToString()

        assertEquals(
            "SETUP rtsp://192.168.42.1:554/liveRTSP/av2/track0 RTSP/1.0\r\n" +
                "CSeq: 3\r\n" +
                "User-Agent: CarDV/1.0\r\n" +
                "Session: ABC123\r\n" +
                "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n" +
                "\r\n",
            request,
        )
    }

    @Test
    fun describeBodyGetsAContentLength() {
        val request = buildRtspRequest(
            method = "DESCRIBE",
            url = "rtsp://host/live",
            cseq = 1,
            session = null,
            userAgent = "test",
            extraHeaders = listOf("Accept: application/sdp"),
            body = "hello",
        ).decodeToString()

        assertEquals(true, request.endsWith("Content-Length: 5\r\n\r\nhello"))
    }

    @Test
    fun readsAResponseThenInterleavedFrames() {
        val response = "RTSP/1.0 200 OK\r\nCSeq: 2\r\nSession: 1234;timeout=30\r\n\r\n"
        val socket = ScriptedSocket(response.encodeToByteArray(), frame(0, byteArrayOf(1, 2, 3)))
        val reader = RtspReader(socket)

        val first = assertIs<Incoming.Response>(reader.next())
        assertEquals(200, first.response.statusCode)
        assertEquals("1234;timeout=30", first.response.header("session"))

        val second = assertIs<Incoming.Frame>(reader.next())
        assertEquals(0, second.channel)
        assertContentEquals(byteArrayOf(1, 2, 3), second.payload)
    }

    @Test
    fun waitsForASplitFrame() {
        val payload = ByteArray(20) { it.toByte() }
        val whole = frame(2, payload)
        val socket = ScriptedSocket(
            whole.copyOfRange(0, 1),
            whole.copyOfRange(1, 3),
            whole.copyOfRange(3, 12),
            whole.copyOfRange(12, whole.size),
        )
        val reader = RtspReader(socket)
        val incoming = assertIs<Incoming.Frame>(reader.next())
        assertEquals(2, incoming.channel)
        assertContentEquals(payload, incoming.payload)
    }

    @Test
    fun skipsJunkBeforeTheNextMessage() {
        val socket = ScriptedSocket(
            "noise".encodeToByteArray(),
            frame(0, byteArrayOf(9, 9)),
        )
        val reader = RtspReader(socket)
        val incoming = assertIs<Incoming.Frame>(reader.next())
        assertContentEquals(byteArrayOf(9, 9), incoming.payload)
    }

    @Test
    fun timeoutReturnsNullWhenNothingArrives() {
        val reader = RtspReader(ScriptedSocket())
        assertNull(reader.next())
    }

    @Test
    fun endOfStreamIsAnError() {
        val socket = ScriptedSocket(ByteArray(0))
        val reader = RtspReader(socket)
        assertFailsWith<LiveException> { reader.next() }
    }

    @Test
    fun readsAResponseWithABodyInOneCall() {
        val body = "v=0\r\nm=video 0 RTP/AVP 96\r\n"
        val text = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Length: ${body.length}\r\n\r\n$body"
        val reader = RtspReader(ScriptedSocket(text.encodeToByteArray()))
        val response = assertIs<Incoming.Response>(reader.next()).response
        assertEquals(body, response.body)
    }
}
