package com.cardv.shared.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdpParserTest {
    /** The shape reported by the camera for `liveRTSP/av2` (PROTOCOL.md). */
    private val cameraSdp = """
        v=0
        o=- 0 0 IN IP4 192.168.42.1
        s=MS8339
        c=IN IP4 0.0.0.0
        t=0 0
        m=video 0 RTP/AVP 96
        a=rtpmap:96 H264/90000
        a=fmtp:96 packetization-mode=1; sprop-parameter-sets=Z2QAH6zZQFAFuhAAAAMAEAAAAwPI8UKg,aO48sA==
        a=control:track0
        m=audio 0 RTP/AVP 97
        a=rtpmap:97 MPEG4-GENERIC/32000/2
        a=fmtp:97 streamtype=5; profile-level-id=1; mode=AAC-hbr; config=1210; SizeLength=13; IndexLength=3; IndexDeltaLength=3
        a=control:track1
    """.trimIndent()

    /** MediaMTX's answer for an ffmpeg-published H.264 + AAC stream. */
    private val mediaMtxSdp = """
        v=0
        o=- 0 0 IN IP4 127.0.0.1
        s=MediaMTX
        c=IN IP4 0.0.0.0
        t=0 0
        a=control:*
        m=video 0 RTP/AVP 96
        a=rtpmap:96 H264/90000
        a=fmtp:96 packetization-mode=1; sprop-parameter-sets=Z2QAH6zZQFAFuhAAAAMAEAAAAwPI8UKg,aO48sA==
        a=control:streamid=0
        m=audio 0 RTP/AVP 97
        a=rtpmap:97 MPEG4-GENERIC/32000/2
        a=fmtp:97 profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=1210
        a=control:streamid=1
    """.trimIndent()

    @Test
    fun parsesBothTracksOfTheCameraStream() {
        val session = SdpParser.parse(cameraSdp, "rtsp://192.168.42.1:554/liveRTSP/av2/")
        assertEquals(2, session.tracks.size)

        val video = session.tracks[0]
        assertTrue(video.isH264)
        assertEquals(96, video.payloadType)
        assertEquals(90_000, video.clockRate)
        assertEquals("track0", video.control)
        assertEquals(2, video.spropParameterSets.size)
        assertTrue(video.spropParameterSets[0].isNotEmpty())

        val audio = session.tracks[1]
        assertTrue(audio.isAac)
        assertEquals(32_000, audio.clockRate)
        assertEquals(2, audio.channels)
        assertEquals("track1", audio.control)
        assertEquals("1210", audio.parameterMeaning("config"))
        assertEquals("13", audio.parameterMeaning("sizelength"))
        assertTrue(audio.parameterMeaning("mode")!!.contains("AAC-hbr", ignoreCase = true))
    }

    @Test
    fun parsesMediaMtxTracksWithStreamIds() {
        val session = SdpParser.parse(mediaMtxSdp, "rtsp://127.0.0.1:8554/live/")
        assertEquals(listOf("streamid=0", "streamid=1"), session.tracks.map { it.control })
        assertTrue(session.tracks[0].isH264)
        assertTrue(session.tracks[1].isAac)
    }

    @Test
    fun parsesCaseInsensitiveFmtpKeys() {
        val session = SdpParser.parse(mediaMtxSdp, "rtsp://host/")
        val audio = session.tracks[1]
        assertEquals("13", audio.parameterMeaning("SizeLength"))
        assertEquals("3", audio.parameterMeaning("IndexLength"))
        assertEquals("1210", audio.parameterMeaning("config"))
    }

    @Test
    fun ignoresSessionsWithoutMedia() {
        val session = SdpParser.parse("v=0\no=- 0 0 IN IP4 0.0.0.0\n", "rtsp://host/")
        assertNull(session.tracks.firstOrNull())
        assertEquals("rtsp://host/", session.baseUrl)
    }

    @Test
    fun marksNonH264VideoAsNotUsable() {
        val sdp = "m=video 0 RTP/AVP 98\na=rtpmap:98 H265/90000\na=control:track0"
        val track = SdpParser.parse(sdp, "rtsp://host/").tracks.single()
        assertTrue(track.isVideo)
        assertTrue(!track.isH264)
        assertNotNull(track.control)
    }
}
