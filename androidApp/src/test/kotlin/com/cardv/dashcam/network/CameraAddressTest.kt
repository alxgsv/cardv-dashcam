package com.cardv.dashcam.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CameraAddressTest {
    @Test
    fun normalizesHostsAndTrailingSlashes() {
        assertEquals("http://192.168.42.1", CameraAddress.normalize("192.168.42.1"))
        assertEquals("http://192.168.42.1", CameraAddress.normalize("  http://192.168.42.1/  "))
        assertEquals("http://10.0.2.2:8080", CameraAddress.normalize("10.0.2.2:8080"))
        assertEquals("https://example.test/camera", CameraAddress.normalize("https://example.test/camera/"))
    }

    @Test
    fun rejectsBlankOrHostlessInput() {
        assertNull(CameraAddress.normalize(""))
        assertNull(CameraAddress.normalize("   "))
        assertNull(CameraAddress.normalize("http://"))
        assertNull(CameraAddress.normalize("http:///path"))
    }

    @Test
    fun extractsTheHost() {
        assertEquals("192.168.42.1", CameraAddress.host("http://192.168.42.1"))
        assertEquals("10.0.2.2", CameraAddress.host("http://10.0.2.2:8080"))
        assertNull(CameraAddress.host("not a url"))
    }

    @Test
    fun onlyTheDocumentedCameraAddressGetsNetworkBinding() {
        assertTrue(CameraAddress.isCameraHost("http://192.168.42.1"))
        assertTrue(CameraAddress.isCameraHost("http://192.168.42.1:80/"))
        assertFalse(CameraAddress.isCameraHost("http://10.0.2.2:8080"))
        assertFalse(CameraAddress.isCameraHost("http://192.168.42.2"))
        assertFalse(CameraAddress.isCameraHost("http://example.test"))
    }
}
