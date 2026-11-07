package com.cardv.dashcam.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.cardv.shared.model.Clip
import java.util.concurrent.TimeUnit

/** Enqueues one resumable download per clip. The worker owns the actual transfer. */
class DownloadScheduler(private val context: Context) {
    fun enqueue(clip: Clip, baseUrl: String) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                Data.Builder()
                    .putString(DownloadWorker.KEY_CLIP_PATH, clip.path)
                    .putString(DownloadWorker.KEY_DISPLAY_NAME, clip.name)
                    .putString(DownloadWorker.KEY_BASE_URL, baseUrl)
                    .putLong(DownloadWorker.KEY_EXPECTED_BYTES, clip.sizeBytes)
                    .putBoolean(DownloadWorker.KEY_IS_VIDEO, clip.isVideo)
                    .build(),
            )
            .addTag(DownloadNaming.TAG)
            .addTag(DownloadNaming.clipTag(clip.path))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            DownloadNaming.uniqueWorkName(clip.path),
            // REPLACE is how the retry button starts a fresh attempt; a saved clip is not shown
            // with a download action any more.
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
