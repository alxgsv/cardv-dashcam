package com.cardv.dashcam.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cardv.dashcam.network.CameraAddress
import com.cardv.dashcam.network.CameraNetwork
import com.cardv.shared.download.DownloadRanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Resumable download (WorkManager + HTTP Range) and MediaStore export.
 *
 * The partial file and a `.total` sidecar survive process death; a retry sends
 * `Range: bytes=<part length>-` and appends. After the bytes are complete, the file is published
 * to `Movies/CarDV` (or `Pictures/CarDV` for snapshots) and the temporary files are removed.
 *
 * Downloads are file reads, not `Config.cgi` commands, so they stay outside the shared
 * `CommandQueue` and may run while the app browses or plays another clip.
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val clipPath = inputData.getString(KEY_CLIP_PATH)
            ?: return@withContext Result.failure(error("missing clip path"))
        val displayName = inputData.getString(KEY_DISPLAY_NAME)
            ?: clipPath.substringAfterLast('/')
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: CameraAddress.defaultBaseUrl()
        val expectedBytes = inputData.getLong(KEY_EXPECTED_BYTES, 0L).takeIf { it > 0 }
        val isVideo = inputData.getBoolean(KEY_IS_VIDEO, true)

        val directory = File(applicationContext.filesDir, DOWNLOAD_DIRECTORY).apply { mkdirs() }
        val part = DownloadNaming.partFile(directory, clipPath)
        val totalFile = DownloadNaming.totalFile(part)

        try {
            val lease = CameraNetwork(applicationContext).connect(baseUrl, bindProcess = false)
            lease.use {
                val client = lease.okHttpClient()
                val storedTotal = totalFile.takeIf { it.isFile }?.readText()?.toLongOrNull()
                var knownTotal = storedTotal
                var existing = part.length()

                if (existing > 0 && knownTotal != null && existing > knownTotal) {
                    // A previous attempt left unusable bytes behind (the camera file changed).
                    part.delete()
                    totalFile.delete()
                    existing = 0
                }

                if (!DownloadRanges.isComplete(existing, knownTotal)) {
                    reportProgress(
                        phase = PHASE_DOWNLOADING,
                        clipPath = clipPath,
                        displayName = displayName,
                        bytes = existing,
                        total = knownTotal ?: expectedBytes,
                    )
                    knownTotal = transfer(
                        client = client,
                        url = baseUrl.trimEnd('/') + (if (clipPath.startsWith('/')) clipPath else "/$clipPath"),
                        part = part,
                        totalFile = totalFile,
                        clipPath = clipPath,
                        displayName = displayName,
                    )
                }

                reportProgress(
                    phase = PHASE_EXPORTING,
                    clipPath = clipPath,
                    displayName = displayName,
                    bytes = part.length(),
                    total = knownTotal ?: expectedBytes,
                )
                val uri = MediaStoreExporter(applicationContext).export(part, displayName, isVideo)
                val length = part.length()

                part.delete()
                totalFile.delete()

                Result.success(
                    workDataOf(
                        KEY_CLIP_PATH to clipPath,
                        KEY_DISPLAY_NAME to displayName,
                        KEY_SAVED_URI to uri.toString(),
                        KEY_TOTAL_BYTES to length,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            // Keep the .part file: cancelling pauses the download, it does not throw bytes away.
            throw cancellation
        } catch (failure: IOException) {
            if (runAttemptCount >= MAX_ATTEMPTS) {
                Result.failure(error("Download failed after ${runAttemptCount + 1} attempts: ${failure.message}"))
            } else {
                Result.retry()
            }
        } catch (failure: SecurityException) {
            Result.failure(error("Storage permission is missing: ${failure.message}"))
        } catch (failure: Exception) {
            Result.failure(error(failure.message ?: failure::class.simpleName ?: "Download failed"))
        }
    }

    /**
     * Append the remaining bytes. Returns the real total from `Content-Range` when known.
     */
    private suspend fun transfer(
        client: OkHttpClient,
        url: String,
        part: File,
        totalFile: File,
        clipPath: String,
        displayName: String,
    ): Long? {
        val existing = part.length()
        val rangeHeader = DownloadRanges.requestHeader(existing)
        val request = Request.Builder()
            .url(url)
            .apply { rangeHeader?.let { header("Range", it) } }
            .build()

        return client.newCall(request).execute().use { response ->
            if (response.code == 416) {
                val total = DownloadRanges.parseContentRange(response.header("Content-Range"))
                    ?.totalBytes
                if (total != null && total > 0 && part.length() >= total) {
                    totalFile.writeText(total.toString())
                    total
                } else {
                    part.delete()
                    totalFile.delete()
                    throw IOException("camera rejected the resume offset; restarting the download")
                }
            } else {
                if (!response.isSuccessful) {
                    throw IOException("camera returned HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("camera returned an empty response")
                val contentRange = DownloadRanges.parseContentRange(response.header("Content-Range"))
                val total = contentRange?.totalBytes ?: body.contentLength().takeIf { it > 0 }

                if (response.code == 206 &&
                    existing > 0 &&
                    contentRange?.start != null &&
                    contentRange.start != existing
                ) {
                    // The server resumed at a different offset than we asked for. Start clean.
                    part.delete()
                    totalFile.delete()
                    throw IOException("camera resumed at ${contentRange.start}, expected $existing")
                }

                val append = response.code == 206 && existing > 0
                total?.let { totalFile.writeText(it.toString()) }

                FileOutputStream(part, append).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        var downloaded = if (append) existing else 0L
                        var lastReport = 0L
                        reportProgress(
                            PHASE_DOWNLOADING,
                            clipPath,
                            displayName,
                            downloaded,
                            total,
                        )
                        while (true) {
                            if (isStopped) throw CancellationException("download stopped")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MILLIS ||
                                (total != null && downloaded >= total)
                            ) {
                                reportProgress(
                                    PHASE_DOWNLOADING,
                                    clipPath,
                                    displayName,
                                    downloaded,
                                    total,
                                )
                                lastReport = now
                            }
                        }
                        reportProgress(
                            PHASE_DOWNLOADING,
                            clipPath,
                            displayName,
                            downloaded,
                            total,
                        )
                    }
                }

                val length = part.length()
                if (total != null && length < total) {
                    throw IOException("download stopped at $length of $total bytes")
                }
                total
            }
        }
    }

    private suspend fun reportProgress(
        phase: String,
        clipPath: String,
        displayName: String,
        bytes: Long,
        total: Long?,
    ) {
        setProgress(
            workDataOf(
                KEY_PHASE to phase,
                KEY_CLIP_PATH to clipPath,
                KEY_DISPLAY_NAME to displayName,
                KEY_BYTES to bytes,
                KEY_TOTAL_BYTES to (total ?: 0L),
            ),
        )
    }

    private fun error(message: String) = workDataOf(KEY_ERROR to message)

    companion object {
        const val KEY_CLIP_PATH = "clip_path"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_BASE_URL = "base_url"
        const val KEY_EXPECTED_BYTES = "expected_bytes"
        const val KEY_IS_VIDEO = "is_video"
        const val KEY_BYTES = "bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_PHASE = "phase"
        const val KEY_ERROR = "error"
        const val KEY_SAVED_URI = "saved_uri"

        const val PHASE_DOWNLOADING = "downloading"
        const val PHASE_EXPORTING = "exporting"

        private const val DOWNLOAD_DIRECTORY = "downloads"
        private const val MAX_ATTEMPTS = 4
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_MILLIS = 250L
    }
}
