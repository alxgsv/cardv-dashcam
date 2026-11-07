package com.cardv.dashcam.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardv.dashcam.BuildConfig
import com.cardv.dashcam.ConnectionPhase
import com.cardv.dashcam.MainUiState
import com.cardv.dashcam.R
import com.cardv.dashcam.network.CameraAddress

@Composable
fun ConnectScreen(
    state: MainUiState,
    onConnect: () -> Unit,
    onUseBaseUrl: (String?, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    var developerOpen by remember { mutableStateOf(false) }
    var versionTaps by remember { mutableIntStateOf(0) }
    var developerUrl by remember(state.baseUrl) {
        mutableStateOf(if (state.debugOverride) state.baseUrl else "")
    }
    var developerLiveUrl by remember(state.liveUrlOverride) {
        mutableStateOf(state.liveUrlOverride.orEmpty())
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(colors.primary, CircleShape),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "DASHCAM",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = CircleShape,
                    color = colors.surfaceVariant,
                    modifier = Modifier.clickable {
                        versionTaps++
                        if (versionTaps >= 5) developerOpen = true
                    },
                ) {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            HeroLens()
            Spacer(Modifier.height(10.dp))

            Text(
                text = buildAnnotatedString {
                    append("Car")
                    withStyle(SpanStyle(color = colors.primary)) { append("DV") }
                },
                style = MaterialTheme.typography.displaySmall,
                color = colors.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "The road,\nreplayed.",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
                lineHeight = 34.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Browse, stream and save clips from your dashcam.\nNo cloud. No account.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))

            ConnectionCard(state = state)
            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onConnect,
                enabled = state.phase != ConnectionPhase.CONNECTING,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.primary,
                    contentColor = colors.onPrimary,
                    disabledContainerColor = colors.surfaceVariant,
                    disabledContentColor = colors.onSurfaceVariant,
                ),
            ) {
                if (state.phase == ConnectionPhase.CONNECTING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = colors.onSurfaceVariant,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = if (state.phase == ConnectionPhase.FAILED) {
                            Icons.Rounded.ErrorOutline
                        } else {
                            Icons.Rounded.Wifi
                        },
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.size(10.dp))
                Text(
                    text = when (state.phase) {
                        ConnectionPhase.CONNECTING -> "Connecting…"
                        ConnectionPhase.FAILED -> "Try again"
                        else -> "Connect to dashcam"
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            TextButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    }
                },
            ) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(6.dp))
                Text("Open Wi-Fi settings")
            }

            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Step("01", "Power on", Modifier.weight(1f))
                Step("02", "Join Wi-Fi", Modifier.weight(1f))
                Step("03", "Connect", Modifier.weight(1f))
            }

            if (BuildConfig.DEBUG && (versionTaps >= 5 || state.debugOverride)) {
                Spacer(Modifier.height(18.dp))
                if (versionTaps >= 5) {
                    TextButton(onClick = { developerOpen = !developerOpen }) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(if (developerOpen) "Hide developer options" else "Developer options")
                    }
                }
                AnimatedVisibility(visible = developerOpen) {
                    DeveloperCard(
                        current = state,
                        url = developerUrl,
                        onUrlChange = { developerUrl = it },
                        liveUrl = developerLiveUrl,
                        onLiveUrlChange = { developerLiveUrl = it },
                        onApply = {
                            onUseBaseUrl(
                                developerUrl.ifBlank { null },
                                developerLiveUrl.ifBlank { null },
                            )
                        },
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Made for the open road",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant.copy(alpha = 0.65f),
                letterSpacing = 1.sp,
            )
        }
    }
}

@Composable
private fun HeroLens() {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(238.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(206.dp)
                .background(
                    Brush.radialGradient(
                        listOf(colors.primary.copy(alpha = 0.16f), Color.Transparent),
                    ),
                    CircleShape,
                ),
        )
        Image(
            painter = painterResource(R.drawable.hero_dashcam),
            contentDescription = null,
            modifier = Modifier.size(218.dp),
        )
    }
}

@Composable
private fun ConnectionCard(state: MainUiState) {
    val colors = MaterialTheme.colorScheme
    val isFailure = state.phase == ConnectionPhase.FAILED
    val isConnecting = state.phase == ConnectionPhase.CONNECTING

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.surfaceVariant.copy(alpha = 0.62f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isFailure) colors.error.copy(alpha = 0.35f) else colors.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        if (isFailure) colors.errorContainer else colors.surface,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    isConnecting -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = colors.primary,
                        strokeWidth = 2.dp,
                    )

                    isFailure -> Icon(
                        Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = colors.error,
                        modifier = Modifier.size(22.dp),
                    )

                    else -> Icon(
                        Icons.Rounded.Wifi,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = when {
                        isConnecting -> "Looking for your dashcam"
                        isFailure -> if (state.hung) "Camera needs a restart" else "Dashcam not found"
                        else -> "Ready when you are"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                Text(
                    text = when {
                        isConnecting -> "Keep the camera on and stay on its Wi-Fi."
                        isFailure -> state.errorMessage ?: "Try again in a moment."
                        else -> "Join the dashcam's Wi-Fi, then connect."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFailure) colors.error else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DeveloperCard(
    current: MainUiState,
    url: String,
    onUrlChange: (String) -> Unit,
    liveUrl: String,
    onLiveUrlChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = colors.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Test camera",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Point the app at a local fake camera. Release builds ignore this.",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Base URL") },
                placeholder = { Text("http://10.0.2.2:8080") },
                supportingText = {
                    Text("Empty = ${CameraAddress.CAMERA_HOST} (the real camera)")
                },
            )
            OutlinedTextField(
                value = liveUrl,
                onValueChange = onLiveUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Live RTSP URL") },
                placeholder = { Text("rtsp://192.168.1.20:8554/live") },
                supportingText = {
                    Text("Empty = rtsp://<camera>:554/liveRTSP/av2")
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onApply,
                    enabled = current.phase != ConnectionPhase.CONNECTING,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Apply")
                }
                TextButton(
                    onClick = {
                        onUrlChange("http://10.0.2.2:8080")
                        onLiveUrlChange("")
                        onApply()
                    },
                    enabled = current.phase != ConnectionPhase.CONNECTING,
                ) {
                    Text("Test camera")
                }
                TextButton(
                    onClick = {
                        onUrlChange("")
                        onLiveUrlChange("")
                        onApply()
                    },
                    enabled = current.phase != ConnectionPhase.CONNECTING,
                ) {
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
private fun Step(number: String, label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number,
                style = MaterialTheme.typography.labelSmall,
                color = colors.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
