package com.cardv.dashcam.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardv.dashcam.ActionFeedback
import com.cardv.dashcam.BuildConfig
import com.cardv.dashcam.CameraAction
import com.cardv.dashcam.MainUiState
import com.cardv.shared.features.SettingsValues
import kotlinx.coroutines.delay

/**
 * M5 camera controls and status.
 *
 * Kept off the library screen on purpose: the library gets one discoverable "Camera" action, and
 * the detailed controls live here instead of turning the main UI into a debug panel.
 */
@Composable
fun CameraScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onRefreshStatus: () -> Unit,
    onToggleMicrophone: () -> Unit,
    onSnapshot: () -> Unit,
    onLockClip: () -> Unit,
    onToggleRecording: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LaunchedEffect(Unit) {
        while (true) {
            onRefreshStatus()
            delay(4_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Camera",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onBackground,
                )
                Text(
                    text = "CONTROLS & STATUS",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(colors.surfaceVariant.copy(alpha = 0.7f), CircleShape)
                    .clickable(onClick = onRefreshStatus),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "Refresh status",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        if (state.busyAction != null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = colors.primary,
                trackColor = Color.Transparent,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (state.hung) HungCard()
            StatusCard(state = state)
            FeedbackBanner(state.feedback)

            Text(
                text = "CONTROLS",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                letterSpacing = 1.2.sp,
            )

            val idle = state.busyAction == null && !state.hung
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlTile(
                    icon = if (state.microphoneOn) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                    title = if (state.microphoneOn) "Mute microphone" else "Unmute microphone",
                    subtitle = if (state.microphoneOn) "Audio is being recorded" else "Microphone is muted",
                    busy = state.busyAction == CameraAction.MIC,
                    enabled = idle,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleMicrophone,
                )
                ControlTile(
                    icon = Icons.Rounded.PhotoCamera,
                    title = "Snapshot",
                    subtitle = "Save a still to Photos",
                    busy = state.busyAction == CameraAction.SNAPSHOT,
                    enabled = idle,
                    modifier = Modifier.weight(1f),
                    onClick = onSnapshot,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ControlTile(
                    icon = Icons.Rounded.Lock,
                    title = "Lock this clip",
                    subtitle = if (state.isRecording) {
                        "Move the current clip to LOCK"
                    } else {
                        "Only while recording"
                    },
                    busy = state.busyAction == CameraAction.LOCK,
                    enabled = idle && state.isRecording,
                    modifier = Modifier.weight(1f),
                    onClick = onLockClip,
                )
                ControlTile(
                    icon = if (state.isRecording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
                    title = if (state.isRecording) "Stop recording" else "Start recording",
                    subtitle = if (state.isRecording) {
                        "Ends the current clip"
                    } else {
                        "Starts a new clip"
                    },
                    busy = state.busyAction == CameraAction.RECORD,
                    enabled = idle,
                    accent = !state.isRecording,
                    modifier = Modifier.weight(1f),
                    onClick = onToggleRecording,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Every command is sent one at a time. If the camera stops answering, " +
                    "restart the car instead of retrying.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun HungCard() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = colors.errorContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "Camera needs a restart",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onErrorContainer,
                )
                Text(
                    "The camera stopped answering after a command. Do not retry — restart the car " +
                        "to reset the dashcam, then reconnect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onErrorContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun StatusCard(state: MainUiState) {
    val colors = MaterialTheme.colorScheme
    val status = state.status
    val recording = state.isRecording
    val free = state.freeMb
    val total = state.totalMb
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceVariant.copy(alpha = 0.62f),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(if (recording) colors.error else colors.onSurfaceVariant, CircleShape),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = if (recording) "Recording" else "Standby",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = colors.surface.copy(alpha = 0.7f),
                ) {
                    Text(
                        text = status?.mode ?: "Videomode",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }

            StatusRow(
                label = "SD card",
                value = state.sdStatus ?: "—",
                icon = Icons.Rounded.Storage,
            )
            StatusRow(
                label = "Free space",
                value = when {
                    free == null -> "—"
                    total == null -> formatSize(free * 1024L * 1024L)
                    else -> "${formatSize(free * 1024L * 1024L)} of ${formatSize(total * 1024L * 1024L)}"
                },
                icon = Icons.Rounded.Storage,
            )
            StatusRow(
                label = "Current file",
                value = state.currentRecordingName ?: "—",
                icon = Icons.Rounded.FiberManualRecord,
            )
            StatusRow(
                label = "Firmware",
                value = firmwareLabel(state.firmware),
                icon = Icons.Rounded.Storage,
            )
            status?.cameraTime?.let { time ->
                StatusRow(
                    label = "Camera clock",
                    value = SettingsValues.formatCameraTime(time),
                    icon = Icons.Rounded.Storage,
                )
            }
            if (BuildConfig.DEBUG && state.liveUrlOverride != null) {
                Text(
                    text = "Debug live URL: ${state.liveUrlOverride}",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/** The full firmware string is very long; show the model/firmware token instead of wrapping. */
private fun firmwareLabel(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    val parts = raw.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    return parts.firstOrNull { it.startsWith("GEELY", ignoreCase = true) }
        ?: parts.lastOrNull()
        ?: raw
}

@Composable
private fun StatusRow(label: String, value: String, icon: ImageVector) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun ControlTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    busy: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        accent -> colors.primary.copy(alpha = 0.16f)
        else -> colors.surfaceVariant.copy(alpha = 0.62f)
    }
    Surface(
        modifier = modifier
            .height(128.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = if (enabled) background else background.copy(alpha = 0.35f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = colors.primary,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (enabled) {
                        if (accent) colors.primary else colors.onSurface
                    } else {
                        colors.onSurfaceVariant.copy(alpha = 0.4f)
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.85f else 0.4f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Shared result banner for the controls and settings screens. */
@Composable
fun FeedbackBanner(feedback: ActionFeedback?) {
    if (feedback == null) return
    val colors = MaterialTheme.colorScheme
    val tone = if (feedback.error) colors.error else colors.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = tone.copy(alpha = 0.12f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (feedback.error) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = tone,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = feedback.message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (feedback.error) colors.error else colors.onSurface,
                fontWeight = if (feedback.error) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}
