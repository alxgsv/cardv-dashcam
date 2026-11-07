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
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.cardv.dashcam.CameraAction
import com.cardv.dashcam.MainUiState
import com.cardv.shared.features.SettingsValues
import com.cardv.shared.safety.SafetyLevel
import com.cardv.shared.safety.Setting
import com.cardv.shared.safety.SettingsCatalog

/**
 * M6 settings.
 *
 * Rows and values come from the shared [SettingsCatalog]; this screen only groups them.
 * `FORBIDDEN` entries are not reachable because [SettingsGroups] derives every section from the
 * catalog. `NEEDS_STANDBY` entries go through the shared stop → write → resume flow, and
 * destructive actions require a typed confirmation.
 */
@Composable
fun SettingsScreen(
    state: MainUiState,
    onBack: () -> Unit,
    onSetExperimental: (Boolean) -> Unit,
    onApplySetting: (Setting, String) -> Unit,
    onFormatSd: () -> Unit,
    onFactoryReset: () -> Unit,
    onDownloadArchive: () -> Unit,
    onSyncClock: () -> Unit,
    onOpenControls: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<Setting?>(null) }
    var destructive by remember { mutableStateOf<Setting?>(null) }
    val idle = state.busyAction == null && !state.hung

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
                .padding(start = 16.dp, end = 18.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleAction(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onBack)
            Spacer(Modifier.size(12.dp))
            Column {
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onBackground,
                )
                Text(
                    text = "CAMERA",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    letterSpacing = 2.sp,
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
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.hung) SettingsHungCard()
            FeedbackBanner(state.feedback)

            SectionTitle("CAMERA")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = colors.surfaceVariant.copy(alpha = 0.62f),
            ) {
                Column {
                    SettingsRow(
                        icon = Icons.Rounded.Schedule,
                        title = "Camera clock",
                        subtitle = "Tap to sync the camera to this phone",
                        value = state.status?.cameraTime?.let(SettingsValues::formatCameraTime),
                        busy = state.busyAction == CameraAction.SYNC_CLOCK,
                        enabled = idle,
                        onClick = onSyncClock,
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Rounded.Videocam,
                        title = "Recording",
                        subtitle = "Stop, start, mic, snapshot and lock",
                        value = if (state.isRecording) "Recording" else "Standby",
                        enabled = true,
                        onClick = onOpenControls,
                    )
                }
            }

            SectionTitle("IMAGE")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = colors.surfaceVariant.copy(alpha = 0.62f),
            ) {
                Column {
                    val rows = SettingsGroups.safe(includeExperimental = false)
                    rows.forEachIndexed { index, setting ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = Icons.Rounded.Videocam,
                            title = settingTitle(setting),
                            subtitle = settingSubtitle(setting),
                            value = currentValue(state, setting),
                            enabled = idle,
                            onClick = { editing = setting },
                        )
                    }
                }
            }

            ExperimentalSection(
                enabled = state.experimentalEnabled,
                idle = idle,
                onToggle = onSetExperimental,
            )
            if (state.experimentalEnabled) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.62f),
                ) {
                    Column {
                        SettingsGroups.experimental().forEachIndexed { index, setting ->
                            if (index > 0) SettingsDivider()
                            SettingsRow(
                                icon = Icons.Rounded.Warning,
                                title = settingTitle(setting),
                                subtitle = settingSubtitle(setting),
                                value = currentValue(state, setting),
                                enabled = idle,
                                warning = true,
                                onClick = { editing = setting },
                            )
                        }
                    }
                }
            }

            SectionTitle("NEEDS RECORDING STOPPED")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = colors.surfaceVariant.copy(alpha = 0.62f),
            ) {
                Column {
                    SettingsGroups.standby().forEachIndexed { index, setting ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = Icons.Rounded.Videocam,
                            title = settingTitle(setting),
                            subtitle = "Stops recording, applies, then restarts it",
                            value = currentValue(state, setting),
                            enabled = idle,
                            onClick = { editing = setting },
                        )
                    }
                }
            }

            SectionTitle("SD CARD & RESET")
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = colors.errorContainer.copy(alpha = 0.35f),
            ) {
                Column {
                    SettingsGroups.destructive().forEachIndexed { index, setting ->
                        if (index > 0) SettingsDivider()
                        SettingsRow(
                            icon = if (setting.name == SettingsCatalog.SD0.name) {
                                Icons.Rounded.SdCard
                            } else {
                                Icons.Rounded.SettingsBackupRestore
                            },
                            title = settingTitle(setting),
                            subtitle = if (setting.name == SettingsCatalog.SD0.name) {
                                "Erases every clip and photo on the camera"
                            } else {
                                "Restores the camera's factory settings"
                            },
                            value = if (setting.name == SettingsCatalog.SD0.name) {
                                state.sdStatus
                            } else {
                                null
                            },
                            enabled = idle,
                            warning = true,
                            onClick = { destructive = setting },
                        )
                    }
                }
            }

            Text(
                text = "Settings marked experimental are untested on this firmware. A wrong one can " +
                    "make the camera stop answering until the car is restarted. The app never " +
                    "sends Flicker, Net, Wi-Fi changes or firmware uploads.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(18.dp))
        }
    }

    editing?.let { setting ->
        ValueChooserDialog(
            setting = setting,
            current = currentValue(state, setting),
            warning = setting.level != SafetyLevel.SAFE,
            onDismiss = { editing = null },
            onSelect = { value ->
                editing = null
                onApplySetting(setting, value)
            },
        )
    }

    destructive?.let { setting ->
        DestructiveDialog(
            setting = setting,
            state = state,
            onDownloadArchive = onDownloadArchive,
            onDismiss = { destructive = null },
            onConfirm = {
                destructive = null
                if (setting.name == SettingsCatalog.SD0.name) onFormatSd() else onFactoryReset()
            },
        )
    }
}

@Composable
private fun ExperimentalSection(
    enabled: Boolean,
    idle: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = if (enabled) {
            colors.errorContainer.copy(alpha = 0.32f)
        } else {
            colors.surfaceVariant.copy(alpha = 0.62f)
        },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Experimental settings",
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.onSurface,
                    )
                    Text(
                        text = "Untested on this camera. Only enable if you accept that the " +
                            "camera may need a restart.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    enabled = idle,
                )
            }
            if (enabled) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Each experimental write is still health-checked. If the camera " +
                            "stops answering, restart the car instead of retrying.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String?,
    enabled: Boolean,
    onClick: () -> Unit,
    busy: Boolean = false,
    warning: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = colors.primary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                icon,
                contentDescription = null,
                tint = if (warning) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant.copy(alpha = 0.55f),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = if (enabled) 0.85f else 0.45f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 2.dp),
            )
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 47.dp, end = 16.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
    )
}

@Composable
private fun SettingsHungCard() {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = colors.errorContainer.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(20.dp),
            )
            Column {
                Text(
                    "Camera needs a restart",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onErrorContainer,
                )
                Text(
                    "Settings are disabled until the dashcam answers again. Restart the car, then " +
                        "reconnect.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onErrorContainer.copy(alpha = 0.85f),
                )
            }
        }
    }
}

@Composable
private fun ValueChooserDialog(
    setting: Setting,
    current: String?,
    warning: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var selected by remember(setting.name) { mutableStateOf(current) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(settingTitle(setting), style = MaterialTheme.typography.titleLarge)
                Text(
                    setting.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (warning) {
                    Text(
                        text = if (setting.level == SafetyLevel.EXPERIMENTAL) {
                            "Untested. The camera may stop answering; restart the car if it does."
                        } else {
                            "Recording will be stopped first, then restarted."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.error,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                setting.values.forEach { value ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selected = value }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = value == selected,
                            onClick = { selected = value },
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurface,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onSelect) },
                enabled = selected != null && selected != current,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DestructiveDialog(
    setting: Setting,
    state: MainUiState,
    onDownloadArchive: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val isFormat = setting.name == SettingsCatalog.SD0.name
    val phrase = if (isFormat) "FORMAT" else "RESET"
    var typed by remember(setting.name) { mutableStateOf("") }
    val activeDownloads = state.archiveDownloadsActive
    val downloading = state.busyAction == CameraAction.DOWNLOAD_ARCHIVE

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (isFormat) Icons.Rounded.SdCard else Icons.Rounded.SettingsBackupRestore,
                contentDescription = null,
                tint = colors.error,
            )
        },
        title = { Text(if (isFormat) "Format the SD card?" else "Factory reset?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (isFormat) {
                        "This erases every loop, SOS and LOCK recording and every photo on the " +
                            "camera's SD card. Clips already downloaded to your phone are safe."
                    } else {
                        "This restores the camera's factory settings and loses your recording " +
                            "preferences. The camera may restart and drop the Wi-Fi connection."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = "Recording is stopped first and restarted afterwards. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.error,
                )
                TextButton(
                    onClick = onDownloadArchive,
                    enabled = !downloading,
                ) {
                    if (downloading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = colors.primary,
                        )
                        Spacer(Modifier.size(8.dp))
                    } else {
                        Icon(
                            Icons.Rounded.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    Text("Download SOS + LOCK first")
                }
                if (activeDownloads.isNotEmpty()) {
                    Text(
                        text = "${activeDownloads.size} archive download(s) still running. " +
                            "Wait for them to finish before continuing.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.error,
                    )
                }
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.uppercase() },
                    singleLine = true,
                    label = { Text("Type $phrase to confirm") },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                    ),
                    supportingText = {
                        Text(
                            text = "This cannot be undone.",
                            color = colors.onSurfaceVariant,
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = typed.trim() == phrase &&
                    activeDownloads.isEmpty() &&
                    state.busyAction == null,
            ) {
                Text(
                    if (isFormat) "Format SD card" else "Factory reset",
                    color = colors.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun currentValue(state: MainUiState, setting: Setting): String? =
    state.status?.let { SettingsValues.currentValue(it, setting) }

private fun settingTitle(setting: Setting): String = when (setting.name) {
    "AWB" -> "White balance"
    "EV" -> "Exposure"
    "DateLogoStamp" -> "Date stamp"
    "VideoClipTime" -> "Clip length"
    "LoopingVideo" -> "Loop recording"
    "Videores" -> "Video resolution"
    "Q-SHOT" -> "Quick shot"
    "HDR" -> "HDR"
    "GSensor" -> "G-sensor sensitivity"
    "MTD" -> "Motion detection"
    "PowerOffDelay" -> "Power-off delay"
    "TVSystem" -> "TV system"
    "UpsideDown" -> "Image orientation"
    "SpotMeter" -> "Spot metering"
    "StatusLights" -> "Status lights"
    "SoundIndicator" -> "Beeps"
    "Beep" -> "Beeps (alias)"
    "PowerSaving" -> "Power saving"
    "ParkingMonitor" -> "Parking monitor"
    "SoundRecord" -> "Sound record"
    "SD0" -> "Format SD card"
    "FactoryReset" -> "Factory reset"
    else -> setting.name
}

private fun settingSubtitle(setting: Setting): String = when (setting.name) {
    "AWB" -> "Colour balance for the light around you"
    "EV" -> "Brightness compensation"
    "DateLogoStamp" -> "Stamp the date onto videos"
    "VideoClipTime" -> "Length of each loop clip"
    "LoopingVideo" -> "Overwrite the oldest clips when full"
    "Videores" -> "Recording resolution and frame rate"
    "GSensor" -> "Event sensitivity for SOS clips"
    "MTD" -> "Motion detection in parking mode"
    else -> setting.description.ifBlank { setting.values.joinToString(" · ") }
}


