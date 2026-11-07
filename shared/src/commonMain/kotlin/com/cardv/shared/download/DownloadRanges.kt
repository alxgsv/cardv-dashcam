package com.cardv.shared.download

/**
 * A parsed `Content-Range` response header.
 *
 * The camera (and every normal HTTP server) supports `bytes <start>-<end>/<total>`. An
 * unsatisfied range answers with a star and the total (`bytes * / <total>`); then [start] and
 * [endInclusive] are null and [totalBytes] is still useful to detect an already-complete file.
 */
data class ContentRange(
    val start: Long?,
    val endInclusive: Long?,
    val totalBytes: Long?,
) {
    val isUnsatisfied: Boolean get() = start == null || endInclusive == null

    /** Number of bytes in this range, when it is a normal (satisfied) range. */
    val length: Long?
        get() = if (start != null && endInclusive != null) endInclusive - start + 1 else null
}

/**
 * HTTP Range helpers shared by the download queue, so resume arithmetic is not reimplemented per
 * platform.
 *
 * The camera lists sizes rounded up to 32 KB clusters, so the listing size is only a hint. The
 * authoritative total comes from `Content-Range`; a download is complete when the local file
 * reaches that total.
 */
object DownloadRanges {
    private val SATISFIED = Regex(
        pattern = """^\s*bytes\s+(\d+)-(\d+)/(\d+|\*)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val UNSATISFIED = Regex(
        pattern = """^\s*bytes\s+\*/(\d+)\s*$""",
        option = RegexOption.IGNORE_CASE,
    )

    /**
     * `Range` header for resuming at [offset]. The first request has no Range header.
     */
    fun requestHeader(offset: Long): String? {
        require(offset >= 0) { "offset must not be negative" }
        return if (offset == 0L) null else "bytes=$offset-"
    }

    /**
     * Parse a `Content-Range` value, case-insensitively (the camera sends `CONTENT-RANGE`).
     * Returns `null` for a missing or malformed header.
     */
    fun parseContentRange(value: String?): ContentRange? {
        val text = value?.trim() ?: return null

        SATISFIED.matchEntire(text)?.let { match ->
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull() // '*' becomes null
            if (end < start) return null
            if (total != null && end >= total) return null
            return ContentRange(start = start, endInclusive = end, totalBytes = total)
        }

        UNSATISFIED.matchEntire(text)?.let { match ->
            val total = match.groupValues[1].toLongOrNull() ?: return null
            return ContentRange(start = null, endInclusive = null, totalBytes = total)
        }

        return null
    }

    /**
     * Where a resumed download should continue.
     *
     * A local file at the real total is complete; a file longer than the total is unusable and
     * restarts from zero. Without a known total, the local length is the resume point.
     */
    fun resumeOffset(existingBytes: Long, totalBytes: Long?): Long {
        if (existingBytes <= 0) return 0
        if (totalBytes == null) return existingBytes
        if (existingBytes > totalBytes) return 0
        if (existingBytes == totalBytes) return totalBytes
        return existingBytes
    }

    fun isComplete(downloadedBytes: Long, totalBytes: Long?): Boolean =
        totalBytes != null && totalBytes > 0 && downloadedBytes >= totalBytes

    /** Whole percent in `0..100`, or `null` while the total is unknown. */
    fun percent(downloadedBytes: Long, totalBytes: Long?): Int? {
        if (totalBytes == null || totalBytes <= 0) return null
        val value = (downloadedBytes.coerceIn(0, totalBytes) * 100) / totalBytes
        return value.toInt().coerceIn(0, 100)
    }
}
