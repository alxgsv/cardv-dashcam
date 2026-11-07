package com.cardv.dashcam.download

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DownloadNamingTest {
    @Test
    fun flattensAndSanitizesTheCameraPath() {
        assertEquals(
            "SD_MOVIE_20220707_162655_002088.MP4",
            DownloadNaming.safeFileName("/SD/MOVIE/20220707_162655_002088.MP4"),
        )
        assertEquals(
            "SD_PHOTO_weird_name_.JPG",
            DownloadNaming.safeFileName("/SD/PHOTO/weird name?.JPG"),
        )
        assertTrue(DownloadNaming.safeFileName("").isNotBlank())
    }

    @Test
    fun lockAndLoopCopiesWithTheSameFileNameDoNotCollide() {
        val directory = File("/tmp/cardv-test")
        val loop = DownloadNaming.partFile(directory, "/SD/MOVIE/20220707_162655_002088.MP4")
        val locked = DownloadNaming.partFile(directory, "/SD/LOCK/20220707_162655_002088.MP4")

        assertNotEquals(loop.name, locked.name)
        assertEquals("${loop.path}.total", DownloadNaming.totalFile(loop).path)
    }

    @Test
    fun clipTagsRoundTrip() {
        val path = "/SD/SOS/20220707_163155_000001.MP4"
        assertEquals(path, DownloadNaming.clipPathFromTags(listOf("other", DownloadNaming.clipTag(path))))
        assertEquals(null, DownloadNaming.clipPathFromTags(emptyList()))
    }
}
