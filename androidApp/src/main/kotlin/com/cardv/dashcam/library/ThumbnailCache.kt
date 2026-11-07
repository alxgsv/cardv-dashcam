package com.cardv.dashcam.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.cardv.dashcam.network.CameraMedia
import com.cardv.shared.model.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Decodes camera thumbnails and caches the bytes on disk, decoded bitmaps in memory.
 *
 * Thumbnail reads overlap their own HTTP range requests (at most [MAX_PARALLEL] at a time) and do
 * not touch the shared command queue.
 */
class ThumbnailCache(context: Context) {
    private val directory: File = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(MEMORY_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val permits = Semaphore(MAX_PARALLEL)

    suspend fun load(clip: Clip, media: CameraMedia): Bitmap? = withContext(Dispatchers.IO) {
        val key = cacheKey(clip)
        memory.get(key)?.let { return@withContext it }

        val cachedFile = File(directory, key)
        val bytes = if (cachedFile.isFile && cachedFile.length() > 0) {
            runCatching { cachedFile.readBytes() }.getOrNull()
        } else {
            null
        }

        val decodedBytes = bytes ?: permits.withPermit { media.thumbnail(clip) }?.also { fetched ->
            runCatching { cachedFile.writeBytes(fetched) }
        }
        decode(decodedBytes)?.also { bitmap -> memory.put(key, bitmap) }
    }

    fun clearMemory() {
        memory.evictAll()
    }

    private fun decode(bytes: ByteArray?): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        options.inSampleSize = sampleSize(options.outWidth, options.outHeight)
        options.inJustDecodeBounds = false
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > TARGET_WIDTH || height / sample > TARGET_HEIGHT) {
            sample *= 2
        }
        return sample
    }

    private fun cacheKey(clip: Clip): String {
        val raw = "${clip.path}|${clip.sizeBytes}|${clip.timestamp}"
        val hash = raw.hashCode().toUInt().toString(16)
        return "thumb_$hash"
    }

    private companion object {
        const val MEMORY_CACHE_KB = 16 * 1024
        const val MAX_PARALLEL = 3
        const val TARGET_WIDTH = 720
        const val TARGET_HEIGHT = 480
    }
}
