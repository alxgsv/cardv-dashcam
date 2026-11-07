package com.cardv.dashcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cardv.dashcam.BuildConfig
import com.cardv.dashcam.ConnectionPhase
import com.cardv.dashcam.MainUiState
import com.cardv.dashcam.network.CameraAddress
import com.cardv.shared.BuildInfo
import com.cardv.shared.platformName

@Composable
fun ConnectScreen(
    state: MainUiState,
    onConnect: () -> Unit,
    onUseBaseUrl: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "CarDV Dashcam",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Browse, play and download recordings straight from the dashcam over its Wi-Fi.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Connect to the dashcam", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "1. Open Android Wi-Fi settings and join the camera's network. It has no internet; " +
                            "choose “Stay connected” if Android asks.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "2. Come back here and press Connect. The app binds traffic to that Wi-Fi and " +
                            "sets the camera clock automatically.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            when (state.phase) {
                ConnectionPhase.CONNECTING -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Column {
                        Text("Connecting…", style = MaterialTheme.typography.titleSmall)
                        Text(
                            state.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                ConnectionPhase.FAILED -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (state.hung) "Dashcam not responding" else "Could not connect",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = state.errorMessage ?: "Unknown error",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.hung) {
                            Text(
                                "A command may have left the camera's small web server busy. " +
                                    "Restart the car (or power-cycle the dashcam) and try again. " +
                                    "Do not keep retrying.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                ConnectionPhase.IDLE, ConnectionPhase.CONNECTED -> Unit
            }

            if (state.phase != ConnectionPhase.CONNECTING) {
                Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.phase == ConnectionPhase.FAILED) "Try again" else "Connect")
                }
            }

            Text(
                text = "Camera address: ${state.baseUrl}" +
                    if (state.debugOverride) "  (debug override)" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (BuildConfig.DEBUG) {
                DebugCameraCard(current = state, onUseBaseUrl = onUseBaseUrl)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Shared core ${BuildInfo.VERSION} · $platformName · app ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DebugCameraCard(
    current: MainUiState,
    onUseBaseUrl: (String?) -> Unit,
) {
    var input by remember { mutableStateOf(if (current.debugOverride) current.baseUrl else "") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Debug camera address", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Base URL override") },
                placeholder = { Text("http://10.0.2.2:8080") },
                supportingText = {
                    Text("Empty means ${CameraAddress.CAMERA_HOST}. Debug builds only; release builds ignore this.")
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onUseBaseUrl(input.ifBlank { null }) },
                    enabled = current.phase != ConnectionPhase.CONNECTING,
                ) {
                    Text("Apply")
                }
                TextButton(
                    onClick = {
                        input = "http://10.0.2.2:8080"
                        onUseBaseUrl("http://10.0.2.2:8080")
                    },
                    enabled = current.phase != ConnectionPhase.CONNECTING,
                ) {
                    Text("Fake camera")
                }
                TextButton(
                    onClick = {
                        input = ""
                        onUseBaseUrl(null)
                    },
                    enabled = current.phase != ConnectionPhase.CONNECTING,
                ) {
                    Text("Reset")
                }
            }
            Text(
                "Fake camera from the emulator: python3 tools/fake-camera/fake_camera.py, " +
                    "then use http://10.0.2.2:8080.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
