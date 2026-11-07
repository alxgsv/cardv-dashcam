package com.cardv.dashcam.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadUiStateTest {
    @Test
    fun progressComesFromSharedRangeMath() {
        val state = DownloadUiState(
            clipPath = "/SD/MOVIE/a.MP4",
            phase = DownloadPhase.DOWNLOADING,
            bytes = 1024,
            totalBytes = 2048,
        )
        assertEquals(0.5f, state.fraction)
        assertTrue(state.isActive)
    }

    @Test
    fun unknownTotalKeepsProgressIndeterminate() {
        val state = DownloadUiState(
            clipPath = "/SD/MOVIE/a.MP4",
            phase = DownloadPhase.DOWNLOADING,
            bytes = 1024,
            totalBytes = null,
        )
        assertNull(state.fraction)
    }

    @Test
    fun savedIsNoLongerActive() {
        val state = DownloadUiState(
            clipPath = "/SD/MOVIE/a.MP4",
            phase = DownloadPhase.SAVED,
            bytes = 2048,
            totalBytes = 2048,
            savedUri = "content://media/external/video/media/1",
        )
        assertFalse(state.isActive)
        assertEquals(1f, state.fraction)
    }
}
