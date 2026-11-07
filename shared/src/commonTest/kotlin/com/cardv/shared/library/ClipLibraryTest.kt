package com.cardv.shared.library

import com.cardv.shared.model.Clip
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipLibraryTest {
    private fun clip(path: String, timestamp: String?): Clip = Clip(
        path = path,
        sizeBytes = 1,
        timestamp = timestamp?.let { LocalDateTime.parse(it) },
    )

    private val older = clip("/SD/MOVIE/20220707_162655_002088.MP4", "2022-07-07T16:26:55")
    private val newer = clip("/SD/MOVIE/20220707_163155_002089.MP4", "2022-07-07T16:31:55")
    private val nextDay = clip("/SD/SOS/20220708_090000_000001.MP4", "2022-07-08T09:00:00")
    private val undated = clip("/SD/MOVIE/broken.MP4", null)

    @Test
    fun newestFirstPutsUndatedLast() {
        val sorted = ClipLibrary.sorted(listOf(older, undated, newer, nextDay), ClipSort.NEWEST_FIRST)

        assertEquals(
            listOf(nextDay.path, newer.path, older.path, undated.path),
            sorted.map { it.path },
        )
    }

    @Test
    fun oldestFirstKeepsUndatedLast() {
        val sorted = ClipLibrary.sorted(listOf(nextDay, undated, older, newer), ClipSort.OLDEST_FIRST)

        assertEquals(
            listOf(older.path, newer.path, nextDay.path, undated.path),
            sorted.map { it.path },
        )
    }

    @Test
    fun nameSortIsCaseInsensitive() {
        val a = clip("/SD/PHOTO/a.JPG", null)
        val b = clip("/SD/PHOTO/B.JPG", null)
        val c = clip("/SD/PHOTO/c.JPG", null)
        val dated = clip("/SD/PHOTO/z.JPG", "2022-07-08T09:00:00")

        assertEquals(
            listOf(dated.path, a.path, b.path, c.path),
            ClipLibrary.sorted(listOf(c, b, a, dated), ClipSort.NAME).map { it.path },
        )
    }

    @Test
    fun dayGroupsFollowTheSortAndKeepUndatedApart() {
        val groups = ClipLibrary.byDay(
            listOf(older, undated, newer, nextDay),
            ClipSort.NEWEST_FIRST,
        )

        assertEquals(3, groups.size)
        assertEquals(nextDay.timestamp?.date, groups[0].date)
        assertEquals(listOf(newer.path, older.path), groups[1].clips.map { it.path })
        assertNull(groups[2].date)
        assertEquals(listOf(undated.path), groups[2].clips.map { it.path })
    }

    @Test
    fun emptyLibraryHasNoGroups() {
        assertEquals(emptyList(), ClipLibrary.byDay(emptyList()))
    }
}
