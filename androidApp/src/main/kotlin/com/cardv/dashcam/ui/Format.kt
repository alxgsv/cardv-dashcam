package com.cardv.dashcam.ui

import com.cardv.shared.model.Clip
import kotlinx.datetime.LocalDate
import java.time.LocalDate as JavaLocalDate
import java.util.Locale

private val MONTHS = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "—"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) {
        "${bytes} ${units[unit]}"
    } else {
        String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
    }
}

fun formatDuration(seconds: Double?): String? {
    if (seconds == null || seconds <= 0) return null
    val total = seconds.toLong()
    return String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
}

fun formatLocalDate(date: LocalDate): String =
    "${date.dayOfMonth} ${MONTHS[date.monthNumber - 1]} ${date.year}"

fun formatClipTimestamp(clip: Clip): String {
    val timestamp = clip.timestamp ?: return clip.name
    return String.format(
        Locale.getDefault(),
        "%02d:%02d · %d %s %d",
        timestamp.hour,
        timestamp.minute,
        timestamp.dayOfMonth,
        MONTHS[timestamp.monthNumber - 1],
        timestamp.year,
    )
}

/** "Today" / "Yesterday" / "7 Nov 2022"; null for files without a usable camera time. */
fun formatDayLabel(date: LocalDate?): String {
    if (date == null) return "Unknown date"
    val activeToday = JavaLocalDate.now().toEpochDay()
    return when (activeToday - date.toEpochDays()) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> formatLocalDate(date)
    }
}
