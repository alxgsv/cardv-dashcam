package com.cardv.shared.live

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RtpDepacketizerTest {
    private fun rtp(
        payload: ByteArray,
        sequence: Int = 1,
        timestamp: Long = 1_000,
        marker: Boolean = false,
        payloadType: Int = 96,
    ): ByteArray {
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
        packet[2] = (sequence shr 8).toByte()
        packet[3] = sequence.toByte()
        for (i in 0..3) {
            packet[4 + i] = ((timestamp shr (8 * (3 - i))) and 0xFF).toByte()
        }
        packet[8] = 0x11
        packet[9] = 0x22
        packet[10] = 0x33
        packet[11] = 0x44
        payload.copyInto(packet, 12)
        return packet
    }

    private fun parse(packet: ByteArray): RtpPacket =
        assertNotNull(RtpPacket.parse(packet, 0, packet.size))

    private fun pushVideo(
        depacketizer: H264Depacketizer,
        payload: ByteArray,
        timestamp: Long = 1_000,
        marker: Boolean,
    ): AccessUnit? = depacketizer.push(payload, 0, payload.size, timestamp, marker)

    @Test
    fun rtpHeaderIsParsed() {
        val packet = parse(rtp(byteArrayOf(0x65, 0x01), sequence = 300, timestamp = 123456, marker = true))
        assertEquals(true, packet.marker)
        assertEquals(96, packet.payloadType)
        assertEquals(300, packet.sequence)
        assertEquals(123456L, packet.timestamp)
        assertEquals(2, packet.payloadLength)
    }

    @Test
    fun singleNalCompletesOnMarker() {
        val depacketizer = H264Depacketizer()
        val unit = assertNotNull(
            pushVideo(depacketizer, byteArrayOf(0x65, 0x01, 0x02), marker = true),
        )
        assertTrue(unit.isKeyFrame)
        assertEquals(1, unit.nalUnits.size)
        assertContentEquals(byteArrayOf(0x65, 0x01, 0x02), unit.nalUnits.single())
    }

    @Test
    fun fuAWithSpsAndPpsIsReassembled() {
        val depacketizer = H264Depacketizer()
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        assertNull(pushVideo(depacketizer, sps, timestamp = 10, marker = false))
        assertNull(pushVideo(depacketizer, pps, timestamp = 10, marker = false))
        assertContentEquals(sps, depacketizer.sps)
        assertContentEquals(pps, depacketizer.pps)

        assertNull(pushVideo(depacketizer, byteArrayOf(0x7C, 0x85.toByte(), 0x09, 0x0A), timestamp = 10, marker = false))
        val unit = assertNotNull(
            pushVideo(depacketizer, byteArrayOf(0x7C, 0x45, 0x0B), timestamp = 10, marker = true),
        )

        assertEquals(3, unit.nalUnits.size)
        assertContentEquals(byteArrayOf(0x65, 0x09, 0x0A, 0x0B), unit.nalUnits[2])
        assertTrue(unit.isKeyFrame)
    }

    @Test
    fun stapAPacketIsSplitIntoNals() {
        val depacketizer = H264Depacketizer()
        val sps = byteArrayOf(0x67, 0x01)
        val pps = byteArrayOf(0x68, 0x02)
        val payload = byteArrayOf(0x78) + byteArrayOf(0x00, 0x02) + sps +
            byteArrayOf(0x00, 0x02) + pps + byteArrayOf(0x00, 0x02) + byteArrayOf(0x65, 0x03)
        val unit = assertNotNull(pushVideo(depacketizer, payload, marker = true))
        assertEquals(3, unit.nalUnits.size)
        assertContentEquals(sps, depacketizer.sps)
        assertContentEquals(pps, depacketizer.pps)
        assertTrue(unit.isKeyFrame)
    }

    @Test
    fun timestampChangeFlushesAnAccessUnitWithoutMarker() {
        val depacketizer = H264Depacketizer()
        assertNull(pushVideo(depacketizer, byteArrayOf(0x41, 0x01), timestamp = 1, marker = false))
        val unit = assertNotNull(pushVideo(depacketizer, byteArrayOf(0x41, 0x02), timestamp = 2, marker = false))
        assertEquals(1, unit.nalUnits.size)
        assertContentEquals(byteArrayOf(0x41, 0x01), unit.nalUnits.single())
        assertTrue(!unit.isKeyFrame)
    }

    @Test
    fun avccPrefixesEveryNalWithItsLength() {
        val unit = AccessUnit(
            listOf(
                byteArrayOf(0x67, 0x01, 0x02),
                byteArrayOf(0x65, 0x03),
            ),
        )
        val avcc = unit.toAvcc()
        assertContentEquals(
            byteArrayOf(
                0, 0, 0, 3, 0x67, 0x01, 0x02,
                0, 0, 0, 2, 0x65, 0x03,
            ),
            avcc,
        )
        assertEquals(avcc.size, unit.byteCount)
    }

    @Test
    fun aacAuHeaderIsParsed() {
        val depacketizer = AacDepacketizer(
            sampleRate = 32_000,
            channels = 2,
            audioSpecificConfig = byteArrayOf(0x12, 0x10),
            sizeLength = 13,
            indexLength = 3,
            indexDeltaLength = 3,
            hasAuHeaders = true,
        )
        // One AU header: size=4, index=0 -> 0x0020, followed by four bytes of AAC.
        val payload = byteArrayOf(0x00, 0x10, 0x00, 0x20, 0x01, 0x02, 0x03, 0x04)
        val frames = depacketizer.push(payload, 0, payload.size, timestamp = 1_000)
        assertEquals(1, frames.size)
        assertContentEquals(byteArrayOf(0x01, 0x02, 0x03, 0x04), frames.single())
    }

    @Test
    fun aacWithoutAuHeadersTreatsPayloadAsOneFrame() {
        val depacketizer = AacDepacketizer(
            sampleRate = 32_000,
            channels = 2,
            audioSpecificConfig = byteArrayOf(0x12, 0x10),
            sizeLength = 13,
            indexLength = 3,
            indexDeltaLength = 3,
            hasAuHeaders = false,
        )
        val payload = byteArrayOf(0x21, 0x22, 0x23)
        val frames = depacketizer.push(payload, 0, payload.size, timestamp = 1_000)
        assertContentEquals(payload, frames.single())
    }

    @Test
    fun audioPresentationTimeUsesTheSampleClock() {
        val depacketizer = AacDepacketizer(
            sampleRate = 32_000,
            channels = 2,
            audioSpecificConfig = byteArrayOf(0x12, 0x10),
            sizeLength = 13,
            indexLength = 3,
            indexDeltaLength = 3,
            hasAuHeaders = false,
        )
        // 1024 samples at 32 kHz = 32 ms per AAC frame.
        assertEquals(1_000_000, depacketizer.presentationTimeMicros(32_000, 0, 0, 0))
        assertEquals(1_032_000, depacketizer.presentationTimeMicros(32_000, 0, 0, 1))
    }

    @Test
    fun synthesisedConfigIsAacLcAt32kStereo() {
        assertContentEquals(
            byteArrayOf(0x12, 0x90.toByte()),
            AacDepacketizer.audioSpecificConfig(32_000, 2),
        )
    }
}
