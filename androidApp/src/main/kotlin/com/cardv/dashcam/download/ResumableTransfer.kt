package com.cardv.dashcam.download

import com.cardv.shared.download.DownloadRanges
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * One resumable HTTP transfer, separated from WorkManager so it can be tested on the JVM.
 *
 * [part] is the file being built and [totalFile] is a sidecar with the authoritative
 * `Content-Range` total. A retry sends `Range: bytes=<part.length>-`; a server that ignores Range
 * (HTTP 200) restarts the file, and a 416 means the part is already complete.
 */
internal class ResumableTransfer(private val client: OkHttpClient) {
    suspend fun download(
        url: String,
        part: File,
        totalFile: File,
        isStopped: () -> Boolean = { false },
        onProgress: suspend (bytes: Long, total: Long?) -> Unit = { _, _ -> },
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
                        onProgress(downloaded, total)
                        while (true) {
                            if (isStopped()) throw CancellationException("download stopped")
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastReport >= PROGRESS_INTERVAL_MILLIS ||
                                (total != null && downloaded >= total)
                            ) {
                                onProgress(downloaded, total)
                                lastReport = now
                            }
                        }
                        onProgress(downloaded, total)
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

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_MILLIS = 250L
    }
}
