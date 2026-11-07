package com.cardv.dashcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cardv.dashcam.download.DownloadPhase
import com.cardv.dashcam.download.DownloadUiState
import com.cardv.dashcam.network.PlaybackHandle

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackScreen(
    handle: PlaybackHandle,
    downloadState: DownloadUiState?,
    onDownload: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var retryKey by remember(handle) { mutableIntStateOf(0) }
    var errorMessage by remember(handle) { mutableStateOf<String?>(null) }

    val player = remember(handle, retryKey) {
        // Progressive HTTP: the camera puts `moov` before `mdat`, so seeking works and the file
        // plays before it is fully downloaded (docs/PROTOCOL.md).
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            OkHttpDataSource.Factory(handle.okHttpClient),
        )
        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(MediaItem.fromUri(handle.url))

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(
                object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        errorMessage = error.message ?: error.errorCodeName
                    }
                },
            )
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(formatClipTimestamp(handle.clip))
                        Text(
                            text = handle.clip.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Back") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
            ) {
                val activePlayer = player
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).also { view ->
                            view.player = activePlayer
                            view.useController = true
                            view.controllerShowTimeoutMs = 4_000
                            view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            view.keepScreenOn = true
                        }
                    },
                    update = { view -> view.player = activePlayer },
                    modifier = Modifier.fillMaxSize(),
                )

                errorMessage?.let { message ->
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Playback failed",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.8f),
                        )
                        Text(
                            "The dashcam may be busy, or the clip may have been overwritten. " +
                                "Seeking and replaying work while the camera is connected.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                        Button(
                            onClick = {
                                errorMessage = null
                                retryKey++
                            },
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = listOfNotNull(
                        handle.clip.resolution,
                        handle.clip.fps?.let { "$it fps" },
                        formatDuration(handle.clip.durationSeconds),
                        formatSize(handle.clip.sizeBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Streaming directly from the camera. Seeking uses HTTP Range; " +
                        "download it to keep a copy.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DownloadFooter(
                    downloadState = downloadState,
                    isVideo = true,
                    onDownload = onDownload,
                )
            }
        }
    }
}

/**
 * Compact download state for the player/photo screens. Permission handling lives in the caller.
 */
@Composable
internal fun DownloadFooter(
    downloadState: DownloadUiState?,
    isVideo: Boolean,
    onDownload: () -> Unit,
) {
    when (downloadState?.phase) {
        null, DownloadPhase.CANCELLED, DownloadPhase.FAILED -> Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (downloadState?.phase == DownloadPhase.FAILED) {
                Text(
                    downloadState.error ?: "Download failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                )
            }
            Button(onClick = onDownload) {
                Text(
                    when (downloadState?.phase) {
                        DownloadPhase.CANCELLED -> "Resume download"
                        DownloadPhase.FAILED -> "Retry download"
                        else -> "Download for offline"
                    },
                )
            }
        }

        DownloadPhase.QUEUED -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
            Text("Queued for download", style = MaterialTheme.typography.labelMedium)
        }

        DownloadPhase.DOWNLOADING -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { downloadState.fraction ?: 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "${formatSize(downloadState.bytes)}" +
                    (downloadState.totalBytes?.let { " / ${formatSize(it)}" } ?: ""),
                style = MaterialTheme.typography.labelSmall,
            )
        }

        DownloadPhase.EXPORTING -> Text(
            "Saving to ${if (isVideo) "Movies" else "Pictures"}/CarDV…",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        DownloadPhase.SAVED -> Text(
            "Saved to ${if (isVideo) "Movies" else "Pictures"}/CarDV",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
