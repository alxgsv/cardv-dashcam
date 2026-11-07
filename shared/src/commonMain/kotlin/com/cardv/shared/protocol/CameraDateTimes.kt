package com.cardv.shared.protocol

import kotlinx.datetime.LocalDateTime

/**
 * Camera time/date parsing.
 *
 * Two formats are used by the firmware:
 * - file listings: `2022-07-07 16:26:55`
 * - `Camera.TimeDate`: `2022/7/20 21/12/49` (no zero padding on month/day/hour)
 */
internal object CameraDateTimes {
    fun parse(value: String): LocalDateTime? {
        val text = value.trim()
        if (text.isEmpty()) return null

        val separator = text.indexOfFirst { it == ' ' || it == 'T' }
        if (separator <= 0) return null
        val datePart = text.substring(0, separator)
        val timePart = text.substring(separator + 1).trim()
        if (timePart.isEmpty()) return null

        val date = datePart.split('/', '-').mapNotNull { it.toIntOrNull() }
        val time = timePart.split(':', '/').mapNotNull { it.toIntOrNull() }
        if (date.size != 3 || time.size < 2) return null

        return runCatching {
            LocalDateTime(
                year = date[0],
                monthNumber = date[1],
                dayOfMonth = date[2],
                hour = time[0],
                minute = time[1],
                second = time.getOrElse(2) { 0 },
            )
        }.getOrNull()
    }

    private fun two(value: Int): String = value.toString().padStart(2, '0')

    /** `TimeSettings` value: `YYYY$MM$DD$hh$mm$ss`, camera-local time. */
    fun toTimeSettings(value: LocalDateTime): String =
        "${value.year}\$${two(value.monthNumber)}\$${two(value.dayOfMonth)}" +
            "\$${two(value.hour)}\$${two(value.minute)}\$${two(value.second)}"
}
