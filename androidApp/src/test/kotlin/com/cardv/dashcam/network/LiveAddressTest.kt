package com.cardv.dashcam.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveAddressTest {
    @Test
    fun fillsInTheDefaultPortAndStream() {
        assertEquals(
            "rtsp://192.168.42.1:554/liveRTSP/av2",
            LiveAddress.normalize("192.168.42.1"),
        )
        assertEquals(
            "rtsp://192.168.1.20:8554/liveRTSP/av2",
            LiveAddress.normalize("rtsp://192.168.1.20:8554"),
        )
    }

    @Test
    fun keepsAnExplicitTestStream() {
        assertEquals(
            "rtsp://192.168.1.20:8554/live",
            LiveAddress.normalize("rtsp://192.168.1.20:8554/live"),
        )
        assertEquals(
            "rtsp://127.0.0.1:554/liveRTSP/v1",
            LiveAddress.normalize("127.0.0.1/liveRTSP/v1"),
        )
    }

    @Test
    fun rejectsUnusableInput() {
        assertNull(LiveAddress.normalize(null))
        assertNull(LiveAddress.normalize("   "))
        assertNull(LiveAddress.normalize("http://192.168.42.1:8080/live"))
        assertNull(LiveAddress.normalize("rtsp://"))
    }
}
