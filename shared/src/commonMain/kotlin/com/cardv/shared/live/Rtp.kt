package com.cardv.shared.live

/**
 * The 12-byte fixed RTP header plus any CSRC list, extension header and padding.
 *
 * The camera and MediaMTX both send plain RTP/AVP, but ignoring these fields would break as
 * soon as a stream carries them, so they are parsed properly.
 */
internal class RtpPacket(
    val marker: Boolean,
    val payloadType: Int,
    val sequence: Int,
    val timestamp: Long,
    val payloadOffset: Int,
    val payloadLength: Int,
) {
    companion object {
        /** Returns `null` for a packet too short to be RTP. */
        fun parse(data: ByteArray, offset: Int, length: Int): RtpPacket? {
            if (length < 12) return null
            val first = data[offset].toInt() and 0xFF
            val version = first shr 6
            if (version != 2) return null
            val padding = (first and 0x20) != 0
            val extension = (first and 0x10) != 0
            val csrcCount = first and 0x0F
            val second = data[offset + 1].toInt() and 0xFF
            val marker = (second and 0x80) != 0
            val payloadType = second and 0x7F
            val sequence = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            val timestamp = ((data[offset + 4].toLong() and 0xFF) shl 24) or
                ((data[offset + 5].toLong() and 0xFF) shl 16) or
                ((data[offset + 6].toLong() and 0xFF) shl 8) or
                (data[offset + 7].toLong() and 0xFF)

            var start = offset + 12 + 4 * csrcCount
            if (extension) {
                if (start + 4 > offset + length) return null
                val words = ((data[start + 2].toInt() and 0xFF) shl 8) or
                    (data[start + 3].toInt() and 0xFF)
                start += 4 + 4 * words
            }
            var end = offset + length
            if (padding) {
                if (start >= end) return null
                val pad = data[end - 1].toInt() and 0xFF
                if (pad > 0 && start + pad <= end) end -= pad
            }
            if (start > end) return null
            return RtpPacket(
                marker = marker,
                payloadType = payloadType,
                sequence = sequence,
                timestamp = timestamp,
                payloadOffset = start,
                payloadLength = end - start,
            )
        }
    }
}

internal class AccessUnit(val nalUnits: List<ByteArray>) {
    val isKeyFrame: Boolean get() = nalUnits.any { (it[0].toInt() and 0x1F) == 5 }
    val byteCount: Int get() = nalUnits.sumOf { it.size + 4 }
}

/**
 * H.264 RTP depacketiser: RFC 6184 packetization modes 0 and 1 (single NAL, STAP-A, FU-A).
 *
 * The camera sends SPS/PPS in-band before every IDR; the client keeps the latest pair so Swift
 * can build a `CMVideoFormatDescription`. Access units are only emitted on the marker bit (or
 * when the timestamp changes, as a recovery path for a lost marker).
 */
internal class H264Depacketizer {
    private val nalUnits = mutableListOf<ByteArray>()
    private var currentTimestamp = -1L
    private var fragmented: ByteArray? = null
    private var fragmentedType = 0

    var sps: ByteArray? = null
        internal set

    var pps: ByteArray? = null
        internal set

    fun push(
        payload: ByteArray,
        offset: Int,
        length: Int,
        timestamp: Long,
        marker: Boolean,
    ): AccessUnit? {
        if (length <= 0) return null
        val previous = if (currentTimestamp >= 0 && timestamp != currentTimestamp) {
            val flushed = if (nalUnits.isNotEmpty()) drain() else null
            fragmented = null
            flushed
        } else {
            null
        }
        currentTimestamp = timestamp
        append(payload, offset, length)
        if (marker && previous == null) {
            return drain()
        }
        return previous
    }

    private fun append(payload: ByteArray, offset: Int, length: Int) {
        val nalType = payload[offset].toInt() and 0x1F
        when {
            nalType in 1..23 -> {
                addNal(payload.copyOfRange(offset, offset + length))
            }

            nalType == 24 -> {
                // STAP-A: two-byte sizes follow the STAP header.
                var index = offset + 1
                val end = offset + length
                while (index + 2 <= end) {
                    val size = ((payload[index].toInt() and 0xFF) shl 8) or
                        (payload[index + 1].toInt() and 0xFF)
                    index += 2
                    if (size <= 0 || index + size > end) break
                    addNal(payload.copyOfRange(index, index + size))
                    index += size
                }
            }

            nalType == 28 -> {
                // FU-A: FU indicator, then an FU header with start/end/type.
                if (length < 2) return
                val fuHeader = payload[offset + 1].toInt() and 0xFF
                val start = (fuHeader and 0x80) != 0
                val end = (fuHeader and 0x40) != 0
                val type = fuHeader and 0x1F
                if (start) {
                    fragmentedType = type
                    fragmented = byteArrayOf(((payload[offset].toInt() and 0xE0) or type).toByte())
                }
                val buffer = fragmented
                if (buffer != null && type == fragmentedType) {
                    val combined = buffer.copyOf(buffer.size + (length - 2))
                    payload.copyInto(combined, buffer.size, offset + 2, offset + length)
                    if (end) {
                        fragmented = null
                        addNal(combined)
                    } else {
                        fragmented = combined
                    }
                }
            }
        }
    }

    private fun addNal(nal: ByteArray) {
        if (nal.isEmpty()) return
        when (nal[0].toInt() and 0x1F) {
            7 -> sps = nal
            8 -> pps = nal
        }
        nalUnits.add(nal)
    }

    private fun drain(): AccessUnit? {
        val units = nalUnits.toList()
        nalUnits.clear()
        fragmented = null
        if (units.isEmpty()) return null
        return AccessUnit(units)
    }
}

/**
 * AAC RTP depacketiser for `MPEG4-GENERIC` (RFC 3640), the format the camera's `av2` stream and
 * MediaMTX use.
 *
 * With AAC-hbr the payload starts with a 16-bit AU-headers length; each AU header carries the
 * Access Unit size in `SizeLength` bits. Streams without AU headers are treated as one frame per
 * packet.
 */
internal class AacDepacketizer(
    private val sampleRate: Int,
    private val channels: Int,
    val audioSpecificConfig: ByteArray,
    private val sizeLength: Int,
    private val indexLength: Int,
    private val indexDeltaLength: Int,
    private val hasAuHeaders: Boolean,
) {
    private val frameDurationMicros: Long =
        if (sampleRate > 0) 1_024L * 1_000_000L / sampleRate else 0L

    fun push(payload: ByteArray, offset: Int, length: Int, timestamp: Long): List<ByteArray> {
        if (length <= 0) return emptyList()
        if (!hasAuHeaders) {
            return listOf(payload.copyOfRange(offset, offset + length))
        }
        if (length < 2) return emptyList()
        val headerBits = ((payload[offset].toInt() and 0xFF) shl 8) or
            (payload[offset + 1].toInt() and 0xFF)
        if (headerBits <= 0) {
            return listOf(payload.copyOfRange(offset + 2, offset + length))
        }
        val headerBytes = (headerBits + 7) / 8
        if (2 + headerBytes > length) return emptyList()

        val sizes = mutableListOf<Int>()
        var bitOffset = 0
        val totalBits = headerBits
        while (bitOffset + sizeLength <= totalBits) {
            sizes.add(readBits(payload, offset + 2, bitOffset, sizeLength))
            bitOffset += sizeLength + indexLength + indexDeltaLength
        }

        val frames = mutableListOf<ByteArray>()
        var dataOffset = offset + 2 + headerBytes
        val end = offset + length
        for (size in sizes) {
            if (size <= 0 || dataOffset + size > end) break
            frames.add(payload.copyOfRange(dataOffset, dataOffset + size))
            dataOffset += size
        }
        if (frames.isEmpty() && dataOffset < end) {
            frames.add(payload.copyOfRange(dataOffset, end))
        }
        return frames
    }

    fun presentationTimeMicros(timestamp: Long, baseTimestamp: Long, baseMicros: Long, index: Int): Long {
        val delta = (timestamp - baseTimestamp) and 0xFFFFFFFFL
        val deltaMicros = if (sampleRate > 0) delta * 1_000_000L / sampleRate else 0L
        return baseMicros + deltaMicros + index * frameDurationMicros
    }

    companion object {
        private fun readBits(data: ByteArray, offset: Int, bitOffset: Int, count: Int): Int {
            var value = 0
            for (i in 0 until count) {
                val absolute = bitOffset + i
                val byteIndex = offset + absolute / 8
                if (byteIndex >= data.size) break
                val bit = (data[byteIndex].toInt() shr (7 - absolute % 8)) and 1
                value = (value shl 1) or bit
            }
            return value
        }

        /** Fallback AudioSpecificConfig (AAC-LC) when the SDP has no `config=`. */
        fun audioSpecificConfig(sampleRate: Int, channels: Int): ByteArray {
            val rates = intArrayOf(
                96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
                16000, 12000, 11025, 8000, 7350,
            )
            val rateIndex = rates.indexOf(sampleRate).let { if (it >= 0) it else 6 }
            val value = (2 shl 11) or (rateIndex shl 7) or ((channels and 0x0F) shl 3)
            return byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())
        }
    }
}
