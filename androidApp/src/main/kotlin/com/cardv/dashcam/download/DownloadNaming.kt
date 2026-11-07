package com.cardv.dashcam.download

import java.io.File

/**
 * Names for the resumable temporary file and WorkManager tags.
 *
 * The full camera path is flattened, so `/SD/MOVIE` and `/SD/LOCK` can never collide even when a
 * clip keeps its loop name (which a manual lock does, see docs/PROTOCOL.md).
 */
object DownloadNaming {
    const val TAG: String = "cardv-download"
    private const val CLIP_TAG_PREFIX = "cardv-clip:"
    private const val MAX_NAME_LENGTH = 120

    fun safeFileName(clipPath: String): String {
        val flattened = clipPath.trim('/').replace('/', '_')
        val sanitized = flattened.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return sanitized.take(MAX_NAME_LENGTH).ifBlank { "clip" }
    }

    fun partFile(directory: File, clipPath: String): File = File(directory, safeFileName(clipPath) + ".part")

    fun totalFile(partFile: File): File = File(partFile.path + ".total")

    fun clipTag(clipPath: String): String = CLIP_TAG_PREFIX + clipPath

    fun clipPathFromTags(tags: Collection<String>): String? =
        tags.firstOrNull { it.startsWith(CLIP_TAG_PREFIX) }?.removePrefix(CLIP_TAG_PREFIX)

    fun uniqueWorkName(clipPath: String): String = "cardv-download:" + clipPath
}
