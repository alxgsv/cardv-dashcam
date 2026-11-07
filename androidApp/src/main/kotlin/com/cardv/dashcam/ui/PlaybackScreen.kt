package com.cardv.dashcam.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
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
import kotlinx.coroutines.delay

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlaybackScreen(
    handle: PlaybackHandle,
    downloadState: DownloadUiState?,
    onDownload: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity
    var retryKey by remember(handle) { mutableIntStateOf(0) }
    var errorMessage by remember(handle) { mutableStateOf<String?>(null) }
    var playing by remember(handle) { mutableStateOf(true) }
    var buffering by remember(handle) { mutableStateOf(true) }
    var positionMs by remember(handle) { mutableLongStateOf(0L) }
    var durationMs by remember(handle) { mutableLongStateOf(0L) }
    var seeking by remember(handle) { mutableStateOf(false) }

    val player = remember(handle, retryKey) {
        // Progressive HTTP: moov comes before mdat on this camera, so seeking works while the
        // clip is still streaming from the camera.
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

                    override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                        playing = isPlayingNow
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        buffering = playbackState == Player.STATE_BUFFERING
                    }
                },
            )
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }
    DisposableEffect(activity) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            // The library is portrait-first; a previous landscape request survives config changes,
            // so restore portrait explicitly instead of relying on the stale value.
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
    DisposableEffect(activity) {
        val window = activity?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, seeking) {
        while (true) {
            if (!seeking) {
                positionMs = player.currentPosition.coerceAtLeast(0L)
                val duration = player.duration
                if (duration != C.TIME_UNSET && duration > 0) durationMs = duration
                buffering = player.playbackState == Player.STATE_BUFFERING
                playing = player.isPlaying
            }
            delay(400)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val activePlayer = player
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).also { view ->
                    view.player = activePlayer
                    view.useController = false
                    view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    view.keepScreenOn = true
                }
            },
            update = { view -> view.player = activePlayer },
            modifier = Modifier.fillMaxSize(),
        )

        // Tapping the picture toggles playback; the slider and buttons sit above this layer.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { activePlayer.playWhenReady = !activePlayer.playWhenReady },
        )

        if (buffering && errorMessage == null) {
            CircularProgressIndicator(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(34.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
            )
        } else if (!playing && errorMessage == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { activePlayer.playWhenReady = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(34.dp),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.9f), Color.Transparent),
                    ),
                ),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatClipTimestamp(handle.clip),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                )
                Text(
                    text = handle.clip.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(24.dp),
            color = Color.Black.copy(alpha = 0.64f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircleAction(
                        icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        label = if (playing) "Pause" else "Play",
                        onClick = { activePlayer.playWhenReady = !activePlayer.playWhenReady },
                    )
                    Spacer(Modifier.size(10.dp))
                    Slider(
                        value = positionMs.coerceIn(0L, durationMs.coerceAtLeast(1L)).toFloat(),
                        onValueChange = {
                            seeking = true
                            positionMs = it.toLong()
                        },
                        onValueChangeFinished = {
                            activePlayer.seekTo(positionMs)
                            seeking = false
                        },
                        valueRange = 0f..durationMs.coerceAtLeast(1L).toFloat(),
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                        ),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatPlaybackTime(positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatPlaybackTime(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = listOfNotNull(
                            handle.clip.resolution,
                            formatDuration(handle.clip.durationSeconds),
                            formatSize(handle.clip.sizeBytes),
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                DownloadPill(
                    downloadState = downloadState,
                    isVideo = true,
                    onDownload = onDownload,
                )
            }
        }

        if (errorMessage != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp),
                shape = RoundedCornerShape(24.dp),
                color = Color.Black.copy(alpha = 0.72f),
            ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(34.dp),
                )
                Text(
                    "Can't play this clip",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Text(
                    "The camera may be busy, or the clip is incomplete. Make sure you're still on the camera's Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
                Button(
                    onClick = {
                        errorMessage = null
                        retryKey++
                    },
                    shape = RoundedCornerShape(50),
                ) {
                    Text("Try again")
                }
            }
            }
        }
    }
}

@Composable
internal fun CircleAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.4f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(21.dp),
        )
    }
}

/** Compact, brand-coloured download state used by the player and photo viewer. */
@Composable
internal fun DownloadPill(
    downloadState: DownloadUiState?,
    isVideo: Boolean,
    onDownload: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    when (downloadState?.phase) {
        null, DownloadPhase.CANCELLED, DownloadPhase.FAILED -> Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (downloadState?.phase == DownloadPhase.FAILED) {
                Text(
                    downloadState?.error ?: "Download failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                    maxLines = 2,
                )
            }
            Button(
                onClick = onDownload,
                modifier = Modifier.height(46.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                ),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(
                    when (downloadState?.phase) {
                        DownloadPhase.CANCELLED -> "Resume download"
                        DownloadPhase.FAILED -> "Retry download"
                        else -> "Save to gallery"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        DownloadPhase.QUEUED -> StatusPill("Queued for download")

        DownloadPhase.DOWNLOADING -> Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color.White.copy(alpha = 0.1f),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isVideo) "Saving clip" else "Saving photo",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        buildString {
                            append(formatSize(downloadState?.bytes ?: 0L))
                            downloadState?.totalBytes?.let { append(" / ${formatSize(it)}") }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.65f),
                    )
                }
                val fraction = downloadState?.fraction
                if (fraction == null) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.primary,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = colors.primary,
                        trackColor = Color.White.copy(alpha = 0.12f),
                    )
                }
            }
        }

        DownloadPhase.EXPORTING -> StatusPill("Saving to ${if (isVideo) "Movies" else "Pictures"}/CarDV…")

        DownloadPhase.SAVED -> Row(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.1f))
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = colors.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Saved to ${if (isVideo) "Movies" else "Pictures"}/CarDV",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.dp,
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
        )
    }
}
