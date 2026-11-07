package com.cardv.dashcam.ui

import android.view.ViewGroup
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.cardv.dashcam.network.CameraNetworkException
import com.cardv.dashcam.network.LiveHandle
import java.io.IOException

/**
 * M4 live view.
 *
 * Uses the separate RTSP stream `liveRTSP/av2` (H.264 + AAC) — never the M2 HTTP playback URL.
 * The camera session already binds the process to the camera Wi-Fi, so the RTSP connection takes
 * the same route; RTP is forced over TCP because the camera only accepts interleaved RTSP.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LiveScreen(
    handle: LiveHandle?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val liveUrl = handle?.url
    var started by remember(liveUrl) { mutableStateOf(liveUrl != null) }
    var attempt by remember(liveUrl) { mutableIntStateOf(0) }
    var error by remember(liveUrl) { mutableStateOf<String?>(null) }
    var connecting by remember(liveUrl) { mutableStateOf(true) }
    var playing by remember(liveUrl) { mutableStateOf(false) }

    // Key on the URL, not on the handle: `LiveHandle.socketFactory` may be a fresh wrapper on each
    // composition, and using it as a key would recreate the player while it is playing.
    val player = remember(liveUrl, started, attempt) {
        if (!started || liveUrl == null) {
            null
        } else {
            val sourceFactory = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(10_000)
            // Bind the RTSP socket to the camera Wi-Fi explicitly (real camera only).
            handle?.socketFactory?.let(sourceFactory::setSocketFactory)
            val mediaSource = sourceFactory.createMediaSource(MediaItem.fromUri(liveUrl))
            ExoPlayer.Builder(context).build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true,
                )
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                addListener(
                    object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_BUFFERING -> connecting = true
                                Player.STATE_READY -> {
                                    connecting = false
                                    playing = isPlaying
                                }

                                Player.STATE_ENDED -> {
                                    connecting = false
                                    playing = false
                                    error = "The camera ended the live stream."
                                }

                                else -> Unit
                            }
                        }

                        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                            playing = isPlayingNow
                        }

                        override fun onPlayerError(playerError: PlaybackException) {
                            connecting = false
                            playing = false
                            error = liveErrorMessage(playerError)
                        }
                    },
                )
            }
        }
    }

    DisposableEffect(player) {
        onDispose { player?.release() }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player?.pause()
                Lifecycle.Event.ON_START -> if (error == null) player?.playWhenReady = true
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The RTSP session timeout is 60 s; Media3's RTSP client sends keep-alives. Keeping this
    // effect makes the "still live" state follow the player rather than the wall clock.

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (player != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).also { view ->
                        view.player = player
                        view.useController = false
                        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        view.keepScreenOn = true
                        view.layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(190.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent),
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
                    text = "Live view",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = "720p · camera audio · RTSP over TCP",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.65f),
                )
            }
            LiveBadge(playing = playing && error == null)
        }

        when {
            liveUrl == null -> LiveMessageCard(
                title = "Not connected",
                body = "Reconnect to the dashcam to start the live stream.",
                icon = Icons.Rounded.ErrorOutline,
                tone = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center),
            )

            error != null -> LiveMessageCard(
                title = "Live view unavailable",
                body = error ?: "",
                icon = Icons.Rounded.ErrorOutline,
                tone = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center),
                action = "Try again",
                onAction = {
                    error = null
                    connecting = true
                    attempt++
                },
            )

            !started -> LiveMessageCard(
                title = "Live view stopped",
                body = "The camera is no longer sending video. Start again when you are ready.",
                icon = Icons.Rounded.Videocam,
                tone = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.Center),
                action = "Start live view",
                onAction = {
                    error = null
                    connecting = true
                    started = true
                    attempt++
                },
            )

            connecting -> Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                )
                Text(
                    "Connecting to live view…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Text(
                    "The camera can take a few seconds to start the stream.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(22.dp),
            color = Color.Black.copy(alpha = 0.68f),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(
                            if (playing) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.35f),
                            CircleShape,
                        ),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            liveUrl == null -> "Disconnected"
                            error != null -> "Stream error"
                            !started -> "Stopped"
                            playing -> "Live"
                            else -> "Starting…"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Text(
                        text = "Live view does not stop the camera from recording.",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.55f),
                    )
                }
                if (started) {
                    Button(
                        onClick = {
                            started = false
                            connecting = false
                            playing = false
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            contentColor = Color.White,
                        ),
                    ) {
                        Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Stop")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveBadge(playing: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (playing) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        } else {
            Color.White.copy(alpha = 0.12f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (playing) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f),
                        CircleShape,
                    ),
            )
            Text(
                text = if (playing) "LIVE" else "IDLE",
                style = MaterialTheme.typography.labelSmall,
                color = if (playing) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.65f),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LiveMessageCard(
    title: String,
    body: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tone: Color,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.padding(28.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.78f),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(34.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
            if (action != null && onAction != null) {
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    Text(action)
                }
            }
        }
    }
}

private fun liveErrorMessage(error: PlaybackException): String = when (error.cause) {
    is CameraNetworkException ->
        "The camera's Wi-Fi is not reachable. Reconnect to it and try again."

    is IOException ->
        "The live stream could not connect. Stay on the camera's Wi-Fi; if it keeps failing, stop recording or restart the camera."

    else ->
        "The camera did not send video. It may be busy recording; try again in a moment."
}
