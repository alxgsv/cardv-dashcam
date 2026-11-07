package com.cardv.dashcam

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardv.dashcam.download.DownloadUiState
import com.cardv.dashcam.network.CameraMedia
import com.cardv.dashcam.network.CameraNetwork
import com.cardv.dashcam.network.CameraSession
import com.cardv.dashcam.network.PlaybackHandle
import com.cardv.dashcam.network.friendlyCameraMessage
import com.cardv.shared.CameraClient
import com.cardv.shared.CameraClientConfig
import com.cardv.shared.features.ClockSync
import com.cardv.shared.features.ClockSyncOutcome
import com.cardv.shared.library.ClipDayGroup
import com.cardv.shared.library.ClipLibrary
import com.cardv.shared.library.ClipSort
import com.cardv.shared.model.CameraStatus
import com.cardv.shared.model.Clip
import com.cardv.shared.model.Folder
import com.cardv.shared.protocol.CameraHungException
import com.cardv.shared.safety.WriteResult
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class ConnectionPhase { IDLE, CONNECTING, CONNECTED, FAILED }

data class MainUiState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val baseUrl: String = CameraClient.DEFAULT_BASE_URL,
    val debugOverride: Boolean = false,
    val errorMessage: String? = null,
    val hung: Boolean = false,
    val firmware: String? = null,
    val sdStatus: String? = null,
    val freeMb: Long? = null,
    val totalMb: Long? = null,
    val recording: Boolean = false,
    val currentRecordingName: String? = null,
    val clockMessage: String? = null,
    val folder: Folder = Folder.DCIM,
    val sort: ClipSort = ClipSort.NEWEST_FIRST,
    val groupByDay: Boolean = true,
    val clipsByFolder: Map<Folder, List<Clip>> = emptyMap(),
    val loadingFolders: Set<Folder> = emptySet(),
    val folderErrors: Map<Folder, String> = emptyMap(),
    val thumbnails: Map<String, Bitmap> = emptyMap(),
    val downloads: Map<String, DownloadUiState> = emptyMap(),
    /** Non-null while the player or photo viewer is open. Kept in the view model so rotation
     *  (including the player's landscape request) does not send the user back to the library. */
    val openClip: Clip? = null,
) {
    val isConnected: Boolean get() = phase == ConnectionPhase.CONNECTED
    val clips: List<Clip> get() = clipsByFolder[folder].orEmpty()
    val sortedClips: List<Clip> get() = ClipLibrary.sorted(clips, sort)
    val groups: List<ClipDayGroup> get() = ClipLibrary.byDay(clips, sort)
    val isLoadingFolder: Boolean get() = folder in loadingFolders
    val folderError: String? get() = folderErrors[folder]

    fun count(folder: Folder): Int? = clipsByFolder[folder]?.size
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as CarDVApplication).container
    private var session: CameraSession? = null
    private var connectJob: Job? = null
    private var prefetchJob: Job? = null
    private val thumbnailsInFlight = mutableSetOf<String>()

    private val _state = MutableStateFlow(
        MainUiState(
            baseUrl = container.preferences.baseUrl(),
            debugOverride = container.preferences.hasDebugOverride,
        ),
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    init {
        observeDownloads()
        connect()
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        connectJob = viewModelScope.launch {
            closeSession()
            prefetchJob?.cancel()
            val baseUrl = container.preferences.baseUrl()
            _state.update {
                it.copy(
                    phase = ConnectionPhase.CONNECTING,
                    baseUrl = baseUrl,
                    debugOverride = container.preferences.hasDebugOverride,
                    errorMessage = null,
                    hung = false,
                    clockMessage = null,
                    firmware = null,
                    sdStatus = null,
                    freeMb = null,
                    totalMb = null,
                    recording = false,
                    currentRecordingName = null,
                    clipsByFolder = emptyMap(),
                    loadingFolders = emptySet(),
                    folderErrors = emptyMap(),
                    thumbnails = emptyMap(),
                    openClip = null,
                )
            }

            try {
                val lease = container.network.connect(baseUrl, bindProcess = true)
                val okHttp = lease.okHttpClient()
                val client = CameraClient(
                    baseUrl = baseUrl,
                    engine = ktorEngine(okHttp),
                    config = CameraClientConfig(micReapplyDelayMillis = 0),
                )

                try {
                    client.health()
                    val status = client.status()
                    val clock = ClockSync(client).sync(status.cameraTime)
                    if (clock.result is WriteResult.Hung || client.isHung) {
                        throw CameraHungException()
                    }

                    val newSession = CameraSession(
                        baseUrl = baseUrl,
                        client = client,
                        lease = lease,
                        media = CameraMedia(client, okHttp),
                    )
                    session = newSession
                    _state.update {
                        it.copy(
                            phase = ConnectionPhase.CONNECTED,
                            hung = false,
                            firmware = status.firmware,
                            sdStatus = status.sdStatus,
                            freeMb = status.freeMb,
                            totalMb = status.totalMb,
                            recording = status.recording,
                            currentRecordingName = if (status.recording) {
                                currentRecordingName(status)
                            } else {
                                null
                            },
                            clockMessage = clockMessage(clock),
                        )
                    }
                } catch (failure: Throwable) {
                    client.close()
                    lease.close()
                    throw failure
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                session = null
                _state.update {
                    it.copy(
                        phase = ConnectionPhase.FAILED,
                        hung = failure is CameraHungException,
                        errorMessage = failure.friendlyCameraMessage(baseUrl),
                    )
                }
                return@launch
            }

            loadFolder(Folder.DCIM, force = true)
            prefetchJob = viewModelScope.launch {
                listOf(Folder.SOS, Folder.LOCK, Folder.PHOTO).forEach { folder ->
                    loadFolder(folder, force = false)
                }
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        prefetchJob?.cancel()
        closeSession()
        _state.update {
            it.copy(
                phase = ConnectionPhase.IDLE,
                errorMessage = null,
                hung = false,
                clockMessage = null,
                clipsByFolder = emptyMap(),
                loadingFolders = emptySet(),
                folderErrors = emptyMap(),
                thumbnails = emptyMap(),
                openClip = null,
            )
        }
    }

    fun openClip(clip: Clip) {
        _state.update { it.copy(openClip = clip) }
    }

    fun closeClip() {
        _state.update { it.copy(openClip = null) }
    }

    fun retry() {
        connect()
    }

    /** Debug builds only; ignored by [CameraPreferences] in release. */
    fun useBaseUrl(raw: String?) {
        container.preferences.setBaseUrl(raw)
        connectJob?.cancel()
        connect()
    }

    fun selectFolder(folder: Folder) {
        _state.update { it.copy(folder = folder) }
        val current = _state.value
        if ((current.clipsByFolder[folder] == null || current.folderErrors[folder] != null) &&
            folder !in current.loadingFolders
        ) {
            viewModelScope.launch { loadFolder(folder, force = true) }
        }
    }

    fun setSort(sort: ClipSort) {
        _state.update { it.copy(sort = sort) }
    }

    fun toggleGrouping() {
        _state.update { it.copy(groupByDay = !it.groupByDay) }
    }

    fun refresh() {
        viewModelScope.launch {
            session?.let { refreshStatusQuietly(it) }
            loadFolder(_state.value.folder, force = true)
        }
    }

    /** Called when a grid item becomes visible; the cache limits parallel range requests. */
    fun ensureThumbnail(clip: Clip) {
        val current = _state.value
        if (!current.isConnected) return
        if (current.thumbnails.containsKey(clip.path)) return
        synchronized(thumbnailsInFlight) {
            if (!thumbnailsInFlight.add(clip.path)) return
        }
        viewModelScope.launch {
            try {
                val media = session?.media ?: return@launch
                val bitmap = container.thumbnails.load(clip, media)
                if (bitmap != null) {
                    _state.update { it.copy(thumbnails = it.thumbnails + (clip.path to bitmap)) }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // A missing thumbnail must never break the library; the placeholder stays.
            } finally {
                synchronized(thumbnailsInFlight) { thumbnailsInFlight.remove(clip.path) }
            }
        }
    }

    fun download(clip: Clip) {
        val current = _state.value
        if (!current.isConnected) return
        if (current.downloads[clip.path]?.isActive == true) return
        viewModelScope.launch {
            val activeSession = session ?: return@launch
            // Re-read Camera.RecFileName immediately before downloading: the clip being
            // recorded must not be copied (docs/PROTOCOL.md).
            refreshStatusQuietly(activeSession)
            if (clip.name == _state.value.currentRecordingName) return@launch
            container.downloads.enqueue(clip, activeSession.baseUrl)
        }
    }

    fun playbackHandle(clip: Clip): PlaybackHandle? {
        val current = session ?: return null
        return PlaybackHandle(
            clip = clip,
            url = current.media.clipUrl(clip),
            okHttpClient = current.okHttpClient,
        )
    }

    fun photoHandle(clip: Clip): PlaybackHandle? = playbackHandle(clip)

    override fun onCleared() {
        closeSession()
        container.thumbnails.clearMemory()
        super.onCleared()
    }

    private suspend fun loadFolder(folder: Folder, force: Boolean) {
        val activeSession = session ?: return
        if (!force && _state.value.clipsByFolder.containsKey(folder)) return

        _state.update {
            it.copy(
                loadingFolders = it.loadingFolders + folder,
                folderErrors = it.folderErrors - folder,
            )
        }
        try {
            val clips = activeSession.client.list(folder)
            _state.update {
                it.copy(
                    clipsByFolder = it.clipsByFolder + (folder to clips),
                    loadingFolders = it.loadingFolders - folder,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            val hung = failure is CameraHungException
            _state.update {
                it.copy(
                    loadingFolders = it.loadingFolders - folder,
                    folderErrors = it.folderErrors + (folder to failure.friendlyCameraMessage(activeSession.baseUrl)),
                    phase = if (hung) ConnectionPhase.FAILED else it.phase,
                    hung = it.hung || hung,
                    errorMessage = if (hung) failure.friendlyCameraMessage(activeSession.baseUrl) else it.errorMessage,
                )
            }
        }
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            container.downloadRepository.observe().collect { downloads ->
                _state.update { it.copy(downloads = downloads) }
            }
        }
    }

    private suspend fun refreshStatusQuietly(activeSession: CameraSession) {
        try {
            val status = activeSession.client.status()
            _state.update {
                it.copy(
                    recording = status.recording,
                    currentRecordingName = if (status.recording) {
                        currentRecordingName(status)
                    } else {
                        null
                    },
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The download request will show the network error if the camera is unreachable.
        }
    }

    private fun closeSession() {
        session?.close()
        session = null
    }

    private fun ktorEngine(okHttp: okhttp3.OkHttpClient): HttpClientEngine =
        OkHttp.create { preconfigured = okHttp }

    private fun currentRecordingName(status: CameraStatus): String? =
        status.recFileName
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }

    private fun clockMessage(outcome: ClockSyncOutcome): String {
        if (outcome.applied) {
            val drift = outcome.driftSeconds
            return if (drift == null || drift == 0L) {
                "Camera clock synced."
            } else {
                "Camera clock synced (was ${humanDuration(abs(drift))} ${if (drift > 0) "behind" else "ahead"})."
            }
        }
        val reason = when (val result = outcome.result) {
            is WriteResult.Rejected -> "camera answered ${result.result}: ${result.detail}"
            is WriteResult.Refused -> result.reason
            is WriteResult.Failed -> result.reason
            is WriteResult.Hung -> "camera stopped answering; restart the car"
            is WriteResult.Applied, null -> "not sent"
        }
        return "Camera clock not updated: $reason."
    }

    private fun humanDuration(seconds: Long): String = when {
        seconds >= 86_400 -> "${seconds / 86_400} d"
        seconds >= 3_600 -> "${seconds / 3_600} h"
        seconds >= 60 -> "${seconds / 60} min"
        else -> "$seconds s"
    }
}
