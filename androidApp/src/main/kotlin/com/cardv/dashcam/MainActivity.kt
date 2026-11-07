package com.cardv.dashcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cardv.dashcam.ui.CameraScreen
import com.cardv.dashcam.ui.ConnectScreen
import com.cardv.dashcam.ui.LibraryScreen
import com.cardv.dashcam.ui.LiveScreen
import com.cardv.dashcam.ui.PhotoScreen
import com.cardv.dashcam.ui.PlaybackScreen
import com.cardv.dashcam.ui.SettingsScreen
import com.cardv.dashcam.ui.theme.CarDVTheme
import com.cardv.shared.model.Clip

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarDVTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                val context = LocalContext.current
                var pendingDownload by remember { mutableStateOf<Clip?>(null) }
                val storagePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    val clip = pendingDownload
                    pendingDownload = null
                    if (granted) {
                        clip?.let(viewModel::download)
                    } else {
                        Toast.makeText(
                            context,
                            "Storage permission is needed to save recordings in Movies/CarDV.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                val downloadWithPermission: (Clip) -> Unit = { clip ->
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingDownload = clip
                        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        viewModel.download(clip)
                    }
                }

                val openClip = state.openClip
                BackHandler(enabled = openClip != null) { viewModel.closeClip() }
                BackHandler(enabled = openClip == null && state.screen != AppScreen.LIBRARY) {
                    viewModel.backToLibrary()
                }

                Box(Modifier.fillMaxSize()) {
                    when {
                        !state.isConnected -> ConnectScreen(
                            state = state,
                            onConnect = viewModel::connect,
                            onUseBaseUrl = viewModel::useBaseUrl,
                        )

                        openClip != null && openClip.isVideo -> {
                            val handle = viewModel.playbackHandle(openClip)
                            if (handle == null) {
                                LaunchedEffect(openClip.path) { viewModel.closeClip() }
                            } else {
                                PlaybackScreen(
                                    handle = handle,
                                    downloadState = state.downloads[openClip.path],
                                    onDownload = { downloadWithPermission(openClip) },
                                    onBack = viewModel::closeClip,
                                )
                            }
                        }

                        openClip != null -> {
                            val handle = viewModel.photoHandle(openClip)
                            if (handle == null) {
                                LaunchedEffect(openClip.path) { viewModel.closeClip() }
                            } else {
                                PhotoScreen(
                                    handle = handle,
                                    downloadState = state.downloads[openClip.path],
                                    onDownload = { downloadWithPermission(openClip) },
                                    onBack = viewModel::closeClip,
                                )
                            }
                        }

                        state.screen == AppScreen.LIVE -> LiveScreen(
                            handle = viewModel.liveHandle(),
                            onBack = viewModel::backToLibrary,
                        )

                        state.screen == AppScreen.CONTROLS -> CameraScreen(
                            state = state,
                            onBack = viewModel::backToLibrary,
                            onRefreshStatus = viewModel::refreshCameraStatus,
                            onToggleMicrophone = viewModel::toggleMicrophone,
                            onSnapshot = viewModel::captureSnapshot,
                            onLockClip = viewModel::lockCurrentClip,
                            onToggleRecording = viewModel::toggleRecording,
                        )

                        state.screen == AppScreen.SETTINGS -> SettingsScreen(
                            state = state,
                            onBack = viewModel::backToLibrary,
                            onSetExperimental = viewModel::setExperimentalEnabled,
                            onApplySetting = viewModel::applySetting,
                            onFormatSd = viewModel::formatSdCard,
                            onFactoryReset = viewModel::factoryReset,
                            onDownloadArchive = viewModel::downloadArchive,
                            onSyncClock = viewModel::syncClockNow,
                            onOpenControls = { viewModel.openScreen(AppScreen.CONTROLS) },
                        )

                        else -> LibraryScreen(
                            state = state,
                            onSelectFolder = viewModel::selectFolder,
                            onSort = viewModel::setSort,
                            onToggleGrouping = viewModel::toggleGrouping,
                            onRefresh = viewModel::refresh,
                            onDisconnect = viewModel::disconnect,
                            onOpen = viewModel::openClip,
                            onThumbnail = viewModel::ensureThumbnail,
                            onDownload = downloadWithPermission,
                            onOpenLive = { viewModel.openScreen(AppScreen.LIVE) },
                            onOpenControls = { viewModel.openScreen(AppScreen.CONTROLS) },
                            onOpenSettings = { viewModel.openScreen(AppScreen.SETTINGS) },
                        )
                    }
                }
            }
        }
    }
}
