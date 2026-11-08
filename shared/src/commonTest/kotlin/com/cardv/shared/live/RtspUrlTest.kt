package com.cardv.shared.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RtspUrlTest {
    @Test
    fun cameraUrlKeepsHostPortAndPath() {
        val url = RtspUrl.parse("rtsp://192.168.42.1:554/liveRTSP/av2")
        assertEquals("192.168.42.1", url.host)
        assertEquals(554, url.port)
        assertEquals("/liveRTSP/av2", url.path)
        assertEquals("rtsp://192.168.42.1:554/liveRTSP/av2", url.requestUrl)
    }

    @Test
    fun portDefaultsTo554AndPathToRoot() {
        val url = RtspUrl.parse("rtsp://camera.local")
        assertEquals(554, url.port)
        assertEquals("/", url.path)
        assertEquals("rtsp://camera.local:554/", url.requestUrl)
    }

    @Test
    fun debugStreamOnAnotherPortIsAllowed() {
        val url = RtspUrl.parse("rtsp://127.0.0.1:8554/live")
        assertEquals(8554, url.port)
        assertEquals("/live", url.path)
    }

    @Test
    fun rejectsNonRtspAndMissingHost() {
        assertFailsWith<LiveException> { RtspUrl.parse("http://127.0.0.1:8554/live") }
        assertFailsWith<LiveException> { RtspUrl.parse("rtsp://") }
        assertFailsWith<LiveException> { RtspUrl.parse("rtsp://:554/live") }
        assertFailsWith<LiveException> { RtspUrl.parse("rtsp://host:notaport/live") }
        assertFailsWith<LiveException> { RtspUrl.parse("rtsp://host:70000/live") }
    }

    @Test
    fun queryIsNotPartOfThePath() {
        val url = RtspUrl.parse("rtsp://127.0.0.1/live?token=abc")
        assertEquals("/live", url.path)
        assertTrue(url.requestUrl.endsWith("/live"))
    }
}
