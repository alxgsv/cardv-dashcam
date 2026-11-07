package com.cardv.dashcam

import android.app.Application
import com.cardv.dashcam.data.CameraPreferences
import com.cardv.dashcam.download.DownloadRepository
import com.cardv.dashcam.download.DownloadScheduler
import com.cardv.dashcam.library.ThumbnailCache
import com.cardv.dashcam.network.CameraNetwork

/** Process-wide singletons. Kept small: the camera session itself belongs to the view model. */
class AppContainer(application: Application) {
    val preferences = CameraPreferences(application)
    val network = CameraNetwork(application)
    val thumbnails = ThumbnailCache(application)
    val downloads = DownloadScheduler(application)
    val downloadRepository = DownloadRepository(application)
}

class CarDVApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
