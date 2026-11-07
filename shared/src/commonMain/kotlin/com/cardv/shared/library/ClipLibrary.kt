package com.cardv.shared.library

import com.cardv.shared.model.Clip
import kotlinx.datetime.LocalDate

/** Order the library shows clips in. [NEWEST_FIRST] is what the app opens with. */
enum class ClipSort {
    NEWEST_FIRST,
    OLDEST_FIRST,
    NAME,
}

/**
 * One day of clips, in display order.
 *
 * [date] is camera-local wall time. It is `null` only when the camera sent no usable `<time>`
 * for that file; the UI shows those last rather than hiding them.
 */
data class ClipDayGroup(
    val date: LocalDate?,
    val clips: List<Clip>,
)

/**
 * Sorting and day grouping for every platform's library.
 *
 * The camera returns files oldest first, and the SOS folder is not chronological at all
 * (docs/PROTOCOL.md), so ordering has to happen here rather than in a UI.
 */
object ClipLibrary {
    /** A stable sort; files without a timestamp always come last. */
    fun sorted(clips: List<Clip>, sort: ClipSort = ClipSort.NEWEST_FIRST): List<Clip> =
        when (sort) {
            ClipSort.NEWEST_FIRST -> clips.sortedWith(
                compareBy<Clip> { it.timestamp == null }
                    .thenByDescending { it.timestamp }
                    .thenByDescending { it.name },
            )

            ClipSort.OLDEST_FIRST -> clips.sortedWith(
                compareBy<Clip> { it.timestamp == null }
                    .thenBy { it.timestamp }
                    .thenBy { it.name },
            )

            ClipSort.NAME -> clips.sortedWith(
                compareBy<Clip> { it.timestamp == null }
                    .thenBy { it.name.lowercase() }
                    .thenBy { it.name }
                    .thenByDescending { it.timestamp },
            )
        }

    /**
     * Sort and split into day groups. Groups follow the sort order (newest day first for
     * [ClipSort.NEWEST_FIRST]); the no-timestamp group, if any, is last.
     */
    fun byDay(clips: List<Clip>, sort: ClipSort = ClipSort.NEWEST_FIRST): List<ClipDayGroup> {
        val groups = LinkedHashMap<LocalDate?, MutableList<Clip>>()
        for (clip in sorted(clips, sort)) {
            groups.getOrPut(clip.timestamp?.date) { mutableListOf() }.add(clip)
        }
        return groups.map { (date, dayClips) -> ClipDayGroup(date, dayClips) }
    }
}
