package com.cardv.dashcam

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cardv.dashcam.download.DownloadPhase
import com.cardv.dashcam.download.DownloadUiState
import com.cardv.dashcam.network.CameraMedia
import com.cardv.dashcam.network.CameraNetwork
import com.cardv.dashcam.network.CameraSession
import com.cardv.dashcam.network.LiveHandle
import com.cardv.dashcam.network.PlaybackHandle
import com.cardv.dashcam.network.friendlyCameraMessage
import com.cardv.shared.CameraClient
import com.cardv.shared.CameraClientConfig
import com.cardv.shared.features.CameraControls
import com.cardv.shared.features.CameraSettings
import com.cardv.shared.features.ClockSync
import com.cardv.shared.features.ClockSyncOutcome
import com.cardv.shared.features.LockOutcome
import com.cardv.shared.features.StandbySettingOutcome
import com.cardv.shared.library.ClipDayGroup
import com.cardv.shared.library.ClipLibrary
import com.cardv.shared.library.ClipSort
import com.cardv.shared.model.CameraStatus
import com.cardv.shared.model.Clip
import com.cardv.shared.model.Folder
import com.cardv.shared.model.LiveStream
import com.cardv.shared.protocol.CameraHungException
import com.cardv.shared.safety.SafetyLevel
import com.cardv.shared.safety.Setting
import com.cardv.shared.safety.SettingsCatalog
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

/** Top-level destinations that are not a clip player. */
enum class AppScreen { LIBRARY, LIVE, CONTROLS, SETTINGS }

/** One camera operation, used to show a spinner on exactly the control that is running. */
enum class CameraAction { RECORD, MIC, SNAPSHOT, LOCK, SYNC_CLOCK, SETTING, DOWNLOAD_ARCHIVE }

data class ActionFeedback(val message: String, val error: Boolean = false)

/** One action result, so failures are drawn as errors instead of green success banners. */
private data class ActionResult(val message: String, val error: Boolean = false)

data class MainUiState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val baseUrl: String = CameraClient.DEFAULT_BASE_URL,
    val debugOverride: Boolean = false,
    val liveUrlOverride: String? = null,
    val errorMessage: String? = null,
    val hung: Boolean = false,
    val firmware: String? = null,
    val sdStatus: String? = null,
    val freeMb: Long? = null,
    val totalMb: Long? = null,
    val recording: Boolean = false,
    val currentRecordingName: String? = null,
    val clockMessage: String? = null,
    /** Last full `Camera.*` snapshot; the controls screen shows it directly. */
    val status: CameraStatus? = null,
    val screen: AppScreen = AppScreen.LIBRARY,
    val experimentalEnabled: Boolean = false,
    val busyAction: CameraAction? = null,
    val feedback: ActionFeedback? = null,
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
    val isRecording: Boolean get() = status?.recording ?: recording
    val microphoneOn: Boolean
        get() = status?.soundRecord?.equals("ON", ignoreCase = true) == true
    val busy: Boolean get() = busyAction != null
    /** True while any archive download (SOS/LOCK) is still running. */
    val archiveDownloadsActive: Map<String, DownloadUiState>
        get() = downloads.filter { (path, download) ->
            download.isActive && (path.startsWith("/SD/SOS/") || path.startsWith("/SD/LOCK/"))
        }
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
    private var statusRefreshInFlight = false

    private val _state = MutableStateFlow(
        MainUiState(
            baseUrl = container.preferences.baseUrl(),
            debugOverride = container.preferences.hasDebugOverride,
            liveUrlOverride = container.preferences.liveUrlOverride(),
            experimentalEnabled = container.preferences.experimentalEnabled(),
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
            val experimental = container.preferences.experimentalEnabled()
            _state.update {
                it.copy(
                    phase = ConnectionPhase.CONNECTING,
                    baseUrl = baseUrl,
                    debugOverride = container.preferences.hasDebugOverride,
                    liveUrlOverride = container.preferences.liveUrlOverride(),
                    experimentalEnabled = experimental,
                    errorMessage = null,
                    hung = false,
                    clockMessage = null,
                    firmware = null,
                    sdStatus = null,
                    freeMb = null,
                    totalMb = null,
                    recording = false,
                    currentRecordingName = null,
                    status = null,
                    screen = AppScreen.LIBRARY,
                    busyAction = null,
                    feedback = null,
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
                    config = CameraClientConfig(
                        micReapplyDelayMillis = 0,
                        allowExperimental = experimental,
                    ),
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
                            status = status,
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
                status = null,
                screen = AppScreen.LIBRARY,
                busyAction = null,
                feedback = null,
                clipsByFolder = emptyMap(),
                loadingFolders = emptySet(),
                folderErrors = emptyMap(),
                thumbnails = emptyMap(),
                openClip = null,
            )
        }
    }

    fun openScreen(screen: AppScreen) {
        _state.update { it.copy(screen = screen) }
    }

    fun backToLibrary() {
        _state.update { it.copy(screen = AppScreen.LIBRARY) }
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
    fun useBaseUrl(raw: String?, liveRaw: String? = null) {
        container.preferences.setBaseUrl(raw)
        container.preferences.setLiveUrlOverride(liveRaw)
        connectJob?.cancel()
        connect()
    }

    /** Debug-only override; otherwise the shared `CameraClient.liveUrl()` camera address. */
    fun liveHandle(): LiveHandle? {
        val active = session ?: return null
        return LiveHandle(
            url = container.preferences.liveUrlOverride() ?: active.client.liveUrl(LiveStream.AV2),
            socketFactory = active.lease.network?.socketFactory,
        )
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

    // ---------------------------------------------------------------------
    // M5 — camera controls
    // ---------------------------------------------------------------------

    fun toggleMicrophone() {
        runAction(CameraAction.MIC) { active ->
            val currentlyOn = _state.value.microphoneOn
            val result = CameraControls(active.client).microphone(!currentlyOn)
            readStatus(active)
            describe(result, if (currentlyOn) "Microphone muted." else "Microphone on.")
        }
    }

    fun captureSnapshot() {
        runAction(CameraAction.SNAPSHOT) { active ->
            val result = CameraControls(active.client).snapshot()
            if (result is WriteResult.Applied) {
                loadFolder(Folder.PHOTO, force = true)
            }
            describe(result, "Snapshot saved to Photos.")
        }
    }

    fun lockCurrentClip() {
        runAction(CameraAction.LOCK) { active ->
            val known = _state.value.clipsByFolder[Folder.LOCK]?.map { it.path }?.toSet()
                ?: runCatching {
                    active.client.list(Folder.LOCK).map { it.path }.toSet()
                }.getOrDefault(emptySet())

            val outcome = CameraControls(active.client).lockCurrentClip(known)
            if (outcome.applied) {
                _state.update {
                    it.copy(clipsByFolder = it.clipsByFolder + (Folder.LOCK to outcome.clips))
                }
                loadFolder(Folder.DCIM, force = true)
                if (!outcome.found) loadFolder(Folder.LOCK, force = true)
            }
            lockMessage(outcome)
        }
    }

    fun toggleRecording() {
        runAction(CameraAction.RECORD) { active ->
            val result = CameraControls(active.client).toggleRecording()
            val status = readStatus(active)
            if (result is WriteResult.Applied) {
                loadFolder(Folder.DCIM, force = true)
            }
            if (result is WriteResult.Applied && status != null) {
                ActionResult(if (status.recording) "Recording started." else "Recording stopped.")
            } else {
                describe(result, "Recording toggled.")
            }
        }
    }

    fun syncClockNow() {
        runAction(CameraAction.SYNC_CLOCK) { active ->
            val outcome = ClockSync(active.client).sync(_state.value.status?.cameraTime)
            readStatus(active)
            _state.update { it.copy(clockMessage = clockMessage(outcome)) }
            if (outcome.applied) {
                ActionResult("Camera clock synced.")
            } else {
                ActionResult(clockMessage(outcome), error = true)
            }
        }
    }

    /**
     * Refresh the status shown on the controls screen. Quiet by default so a poll failure never
     * replaces an action result with a network error banner.
     */
    fun refreshCameraStatus() {
        if (statusRefreshInFlight || _state.value.busyAction != null) return
        val active = session ?: return
        statusRefreshInFlight = true
        viewModelScope.launch {
            try {
                refreshStatusQuietly(active)
            } finally {
                statusRefreshInFlight = false
            }
        }
    }

    // ---------------------------------------------------------------------
    // M6 — settings
    // ---------------------------------------------------------------------

    fun setExperimentalEnabled(enabled: Boolean) {
        container.preferences.setExperimentalEnabled(enabled)
        session?.client?.setExperimentalAllowed(enabled)
        _state.update { it.copy(experimentalEnabled = enabled) }
    }

    fun applySetting(setting: Setting, value: String) {
        val level = SettingsCatalog.levelOf(setting.name)
        if (level == SafetyLevel.FORBIDDEN) {
            _state.update {
                it.copy(feedback = ActionFeedback("This setting is blocked for safety.", true))
            }
            return
        }
        if (level == SafetyLevel.EXPERIMENTAL && !_state.value.experimentalEnabled) {
            _state.update {
                it.copy(
                    feedback = ActionFeedback("Enable experimental settings first.", true),
                )
            }
            return
        }

        runAction(CameraAction.SETTING) { active ->
            if (level == SafetyLevel.NEEDS_STANDBY) {
                val outcome = CameraSettings(active.client).setNeedsStandby(setting, value)
                standbyMessage(active, setting, value, outcome)
            } else {
                val result = active.client.setSetting(setting, value)
                readStatus(active)
                if (result is WriteResult.Applied) {
                    reloadAfterDestructiveChange(active, setting)
                }
                describe(result, "${setting.name} set to $value.")
            }
        }
    }

    /** Typed confirmation happens in the UI; this is the actual SD format flow. */
    fun formatSdCard() {
        applySetting(SettingsCatalog.SD0, "format")
    }

    /** Typed confirmation happens in the UI; this is the actual factory reset flow. */
    fun factoryReset() {
        applySetting(SettingsCatalog.FACTORY_RESET, "Camera")
    }

    /**
     * Queue every SOS and LOCK clip for download. The settings screen offers this before a
     * destructive action; downloads keep running in WorkManager even if the user stays here.
     */
    fun downloadArchive() {
        runAction(CameraAction.DOWNLOAD_ARCHIVE) { active ->
            var queued = 0
            val updated = mutableMapOf<Folder, List<Clip>>()
            for (folder in listOf(Folder.SOS, Folder.LOCK)) {
                val clips = try {
                    active.client.list(folder)
                } catch (_: Exception) {
                    _state.value.clipsByFolder[folder].orEmpty()
                }
                updated[folder] = clips
                clips.forEach { clip ->
                    val alreadySaved =
                        _state.value.downloads[clip.path]?.phase == DownloadPhase.SAVED
                    if (!alreadySaved) {
                        container.downloads.enqueue(clip, active.baseUrl)
                        queued++
                    }
                }
            }
            _state.update { it.copy(clipsByFolder = it.clipsByFolder + updated) }
            ActionResult(
                if (queued == 0) {
                    "No SOS or LOCK clips to download."
                } else {
                    "Queued $queued SOS/LOCK ${if (queued == 1) "clip" else "clips"} for download."
                },
            )
        }
    }

    // ---------------------------------------------------------------------
    // Library helpers
    // ---------------------------------------------------------------------

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

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

    /** Run one camera operation with a single busy flag and one readable result banner. */
    private fun runAction(action: CameraAction, block: suspend (CameraSession) -> ActionResult) {
        if (_state.value.busyAction != null) return
        val active = session
        if (active == null) {
            _state.update {
                it.copy(feedback = ActionFeedback("Not connected to the camera.", true))
            }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(busyAction = action, feedback = null) }
            var outcome: ActionResult? = null
            try {
                outcome = block(active)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                outcome = ActionResult(failure.friendlyCameraMessage(active.baseUrl), error = true)
                if (failure is CameraHungException) {
                    _state.update { it.copy(hung = true) }
                }
            } finally {
                val hung = active.client.isHung || _state.value.hung
                _state.update {
                    it.copy(
                        busyAction = null,
                        hung = hung,
                        feedback = outcome?.let { result ->
                            ActionFeedback(result.message, result.error || hung)
                        } ?: it.feedback,
                    )
                }
            }
        }
    }

    private suspend fun readStatus(active: CameraSession): CameraStatus? =
        try {
            active.client.status().also(::applyStatus)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private fun applyStatus(status: CameraStatus) {
        _state.update {
            it.copy(
                status = status,
                firmware = status.firmware ?: it.firmware,
                sdStatus = status.sdStatus ?: it.sdStatus,
                freeMb = status.freeMb ?: it.freeMb,
                totalMb = status.totalMb ?: it.totalMb,
                recording = status.recording,
                currentRecordingName = if (status.recording) {
                    currentRecordingName(status)
                } else {
                    null
                },
            )
        }
    }

    private suspend fun refreshStatusQuietly(activeSession: CameraSession) {
        try {
            applyStatus(activeSession.client.status())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // A polling failure must not replace an action result with a network error.
        }
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

    private suspend fun reloadAfterDestructiveChange(active: CameraSession, setting: Setting) {
        if (setting.name != SettingsCatalog.SD0.name &&
            setting.name != SettingsCatalog.FACTORY_RESET.name
        ) {
            return
        }
        _state.update { it.copy(clipsByFolder = emptyMap(), thumbnails = emptyMap()) }
        for (folder in Folder.entries) {
            loadFolder(folder, force = true)
        }
        refreshStatusQuietly(active)
    }

    private suspend fun standbyMessage(
        active: CameraSession,
        setting: Setting,
        value: String,
        outcome: StandbySettingOutcome,
    ): ActionResult {
        val statusAfter = outcome.statusAfter
        if (statusAfter != null) applyStatus(statusAfter) else readStatus(active)

        val applied = outcome.result is WriteResult.Applied
        val base = when (val result = outcome.result) {
            is WriteResult.Applied -> ActionResult("${setting.name} set to $value.")
            else -> describe(result, "${setting.name} set to $value.")
        }
        val notes = buildList {
            if (outcome.verified == false) {
                add("The SD card did not report READY; check it before recording again.")
            }
            if (outcome.recordingLeftStopped) {
                add("Recording was not restarted — start it again from Camera.")
            }
        }
        if (applied) {
            reloadAfterDestructiveChange(active, setting)
        }
        val needsAttention = !applied || outcome.verified == false || outcome.recordingLeftStopped
        return ActionResult(
            message = (listOf(base.message) + notes).joinToString(" "),
            error = base.error || needsAttention,
        )
    }

    private fun lockMessage(outcome: LockOutcome): ActionResult = when {
        !outcome.applied -> describe(outcome.write, "Clip locked.")
        outcome.found -> {
            val name = outcome.newPaths.first().substringAfterLast('/')
            ActionResult("Locked $name.")
        }

        else -> ActionResult("Lock sent. LOCK has not listed the clip yet; try Refresh in a moment.")
    }

    private fun describe(result: WriteResult<*>, success: String): ActionResult = when (result) {
        is WriteResult.Applied -> ActionResult(success)
        is WriteResult.Rejected -> ActionResult(
            "Camera refused: ${result.detail} (${result.result.code})",
            error = true,
        )

        is WriteResult.Refused -> ActionResult(result.reason, error = true)
        is WriteResult.Hung -> ActionResult(
            "The camera stopped responding. Restart the car to reset it.",
            error = true,
        )

        is WriteResult.Failed -> ActionResult(result.reason, error = true)
    }

    private fun observeDownloads() {
        viewModelScope.launch {
            container.downloadRepository.observe().collect { downloads ->
                _state.update { it.copy(downloads = downloads) }
            }
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
