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
import com.cardv.dashcam.ui.ConnectScreen
import com.cardv.dashcam.ui.LibraryScreen
import com.cardv.dashcam.ui.PhotoScreen
import com.cardv.dashcam.ui.PlaybackScreen
import com.cardv.dashcam.ui.theme.CarDVTheme
import com.cardv.shared.model.Clip

private sealed interface AppRoute {
    data object Library : AppRoute
    data class Playback(val clip: Clip) : AppRoute
    data class Photo(val clip: Clip) : AppRoute
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CarDVTheme {
                val viewModel: MainViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                var route by remember { mutableStateOf<AppRoute>(AppRoute.Library) }

                LaunchedEffect(state.phase) {
                    if (state.phase != ConnectionPhase.CONNECTED) route = AppRoute.Library
                }

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

                BackHandler(enabled = route != AppRoute.Library) { route = AppRoute.Library }

                Box(Modifier.fillMaxSize()) {
                    if (!state.isConnected) {
                        ConnectScreen(
                            state = state,
                            onConnect = viewModel::connect,
                            onUseBaseUrl = viewModel::useBaseUrl,
                        )
                    } else {
                        when (val current = route) {
                            AppRoute.Library -> LibraryScreen(
                                state = state,
                                onSelectFolder = viewModel::selectFolder,
                                onSort = viewModel::setSort,
                                onToggleGrouping = viewModel::toggleGrouping,
                                onRefresh = viewModel::refresh,
                                onDisconnect = viewModel::disconnect,
                                onOpen = { clip ->
                                    route = if (clip.isVideo) {
                                        AppRoute.Playback(clip)
                                    } else {
                                        AppRoute.Photo(clip)
                                    }
                                },
                                onThumbnail = viewModel::ensureThumbnail,
                                onDownload = downloadWithPermission,
                            )

                            is AppRoute.Playback -> {
                                val handle = viewModel.playbackHandle(current.clip)
                                if (handle == null) {
                                    LaunchedEffect(current.clip.path) { route = AppRoute.Library }
                                } else {
                                    PlaybackScreen(
                                        handle = handle,
                                        downloadState = state.downloads[current.clip.path],
                                        onDownload = { downloadWithPermission(current.clip) },
                                        onBack = { route = AppRoute.Library },
                                    )
                                }
                            }

                            is AppRoute.Photo -> {
                                val handle = viewModel.photoHandle(current.clip)
                                if (handle == null) {
                                    LaunchedEffect(current.clip.path) { route = AppRoute.Library }
                                } else {
                                    PhotoScreen(
                                        handle = handle,
                                        downloadState = state.downloads[current.clip.path],
                                        onDownload = { downloadWithPermission(current.clip) },
                                        onBack = { route = AppRoute.Library },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
