package com.cardv.shared.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadRangesTest {
    @Test
    fun parsesASatisfiedRange() {
        val range = DownloadRanges.parseContentRange("bytes 0-1023/489291776")

        assertEquals(0L, range?.start)
        assertEquals(1023L, range?.endInclusive)
        assertEquals(489_291_776L, range?.totalBytes)
        assertEquals(1024L, range?.length)
        assertFalse(range!!.isUnsatisfied)
    }

    @Test
    fun parsesTheCamerasUppercaseHeader() {
        val range = DownloadRanges.parseContentRange("BYTES 1024-2047/2048")

        assertEquals(1024L, range?.start)
        assertEquals(2047L, range?.endInclusive)
        assertEquals(2048L, range?.totalBytes)
    }

    @Test
    fun parsesAnUnsatisfiedRange() {
        val range = DownloadRanges.parseContentRange("bytes */2048")

        assertNull(range?.start)
        assertNull(range?.endInclusive)
        assertEquals(2048L, range?.totalBytes)
        assertTrue(range!!.isUnsatisfied)
    }

    @Test
    fun rejectsMalformedRanges() {
        assertNull(DownloadRanges.parseContentRange(null))
        assertNull(DownloadRanges.parseContentRange(""))
        assertNull(DownloadRanges.parseContentRange("bytes 10-5/100"))
        assertNull(DownloadRanges.parseContentRange("bytes 0-100/100"))
        assertNull(DownloadRanges.parseContentRange("items 0-10/100"))
        assertNull(DownloadRanges.parseContentRange("bytes nope"))
    }

    @Test
    fun requestHeaderOmitsRangeForTheFirstByte() {
        assertNull(DownloadRanges.requestHeader(0))
        assertEquals("bytes=1-", DownloadRanges.requestHeader(1))
        assertEquals("bytes=4096-", DownloadRanges.requestHeader(4096))
    }

    @Test
    fun resumeOffsetHandlesCompleteAndOversizedFiles() {
        assertEquals(0, DownloadRanges.resumeOffset(0, 2048))
        assertEquals(1000, DownloadRanges.resumeOffset(1000, 2048))
        assertEquals(2048, DownloadRanges.resumeOffset(2048, 2048))
        assertEquals(0, DownloadRanges.resumeOffset(5000, 2048))
        assertEquals(1000, DownloadRanges.resumeOffset(1000, null))
    }

    @Test
    fun completenessAndPercent() {
        assertTrue(DownloadRanges.isComplete(2048, 2048))
        assertFalse(DownloadRanges.isComplete(2047, 2048))
        assertFalse(DownloadRanges.isComplete(2048, null))
        assertEquals(50, DownloadRanges.percent(1024, 2048))
        assertEquals(100, DownloadRanges.percent(5000, 2048))
        assertNull(DownloadRanges.percent(10, null))
    }
}
