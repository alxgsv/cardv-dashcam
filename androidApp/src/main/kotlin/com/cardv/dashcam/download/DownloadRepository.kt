package com.cardv.dashcam.download

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cardv.shared.download.DownloadRanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

enum class DownloadPhase { QUEUED, DOWNLOADING, EXPORTING, SAVED, FAILED, CANCELLED }

data class DownloadUiState(
    val clipPath: String,
    val phase: DownloadPhase,
    val bytes: Long = 0,
    val totalBytes: Long? = null,
    val displayName: String? = null,
    val error: String? = null,
    val savedUri: String? = null,
) {
    val isActive: Boolean
        get() = phase == DownloadPhase.QUEUED ||
            phase == DownloadPhase.DOWNLOADING ||
            phase == DownloadPhase.EXPORTING

    /** `null` while the total is unknown. */
    val fraction: Float?
        get() = DownloadRanges.percent(bytes, totalBytes)?.let { it / 100f }
}

/** WorkManager is the download queue state; this maps its WorkInfo records into UI state. */
class DownloadRepository(private val context: Context) {
    fun observe(): Flow<Map<String, DownloadUiState>> =
        WorkManager.getInstance(context)
            .getWorkInfosByTagFlow(DownloadNaming.TAG)
            .map { infos ->
                infos.mapNotNull { info ->
                    val clipPath = DownloadNaming.clipPathFromTags(info.tags) ?: return@mapNotNull null
                    clipPath to info.toUiState(clipPath)
                }.toMap()
            }
            .catch { emit(emptyMap()) }

    private fun WorkInfo.toUiState(clipPath: String): DownloadUiState {
        val progress = progress
        val output = outputData
        val bytes = progress.getLong(DownloadWorker.KEY_BYTES, 0)
        val total = progress.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0)
        val displayName = output.getString(DownloadWorker.KEY_DISPLAY_NAME)
            ?: progress.getString(DownloadWorker.KEY_DISPLAY_NAME)
        val phase = when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadPhase.QUEUED
            WorkInfo.State.RUNNING -> when (progress.getString(DownloadWorker.KEY_PHASE)) {
                DownloadWorker.PHASE_EXPORTING -> DownloadPhase.EXPORTING
                else -> DownloadPhase.DOWNLOADING
            }

            WorkInfo.State.SUCCEEDED -> DownloadPhase.SAVED
            WorkInfo.State.FAILED -> DownloadPhase.FAILED
            WorkInfo.State.CANCELLED -> DownloadPhase.CANCELLED
        }
        return DownloadUiState(
            clipPath = clipPath,
            phase = phase,
            bytes = if (phase == DownloadPhase.SAVED && total > 0) total else bytes,
            totalBytes = total.takeIf { it > 0 },
            displayName = displayName,
            error = if (phase == DownloadPhase.FAILED) {
                output.getString(DownloadWorker.KEY_ERROR) ?: "Download failed"
            } else {
                null
            },
            savedUri = output.getString(DownloadWorker.KEY_SAVED_URI),
        )
    }
}
