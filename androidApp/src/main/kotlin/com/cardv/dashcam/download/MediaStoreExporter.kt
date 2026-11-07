package com.cardv.dashcam.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * Publishes a finished download into the gallery.
 *
 * Android 10+ uses MediaStore with `RELATIVE_PATH`, so no storage permission is needed. Android
 * 8-9 writes to the public Movies/Pictures directory and asks the media scanner to index it; the
 * UI requests WRITE_EXTERNAL_STORAGE on those versions before enqueueing a download.
 */
class MediaStoreExporter(private val context: Context) {
    fun export(source: File, displayName: String, isVideo: Boolean): Uri {
        val safeName = displayName.substringAfterLast('/').ifBlank { source.name }
        val mimeType = if (isVideo) "video/mp4" else "image/jpeg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportScoped(source, safeName, mimeType, isVideo)
        } else {
            @Suppress("DEPRECATION")
            exportLegacy(source, safeName, mimeType, isVideo)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun exportScoped(
        source: File,
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
    ): Uri {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) +
            "/CarDV"

        findExisting(collection, displayName, relativePath)?.let { existing ->
            if (sizeOf(collection, existing) == source.length()) return existing
            runCatching { resolver.delete(existing, null, null) }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("could not create a gallery entry for $displayName")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: throw IOException("could not open the gallery entry for $displayName")

            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, ready, null, null)
            return uri
        } catch (failure: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw failure
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findExisting(
        collection: Uri,
        displayName: String,
        relativePath: String,
    ): Uri? {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
        val arguments = arrayOf(displayName, "$relativePath%")
        return context.contentResolver.query(
            collection,
            projection,
            selection,
            arguments,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(0)
            Uri.withAppendedPath(collection, id.toString())
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun sizeOf(collection: Uri, uri: Uri): Long =
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        } ?: 0L

    @Suppress("DEPRECATION")
    private fun exportLegacy(
        source: File,
        displayName: String,
        mimeType: String,
        isVideo: Boolean,
    ): Uri {
        val publicDirectory = Environment.getExternalStoragePublicDirectory(
            if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
        )
        val directory = File(publicDirectory, "CarDV")
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("could not create ${directory.absolutePath}")
        }
        val destination = File(directory, displayName)
        source.inputStream().use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(destination.absolutePath),
            arrayOf(mimeType),
            null,
        )
        return Uri.fromFile(destination)
    }
}
