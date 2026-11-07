package com.cardv.dashcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.cardv.dashcam.download.DownloadUiState
import com.cardv.dashcam.network.PlaybackHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(
    handle: PlaybackHandle,
    downloadState: DownloadUiState?,
    onDownload: () -> Unit,
    onBack: () -> Unit,
) {
    var retryKey by remember(handle.url) { mutableIntStateOf(0) }
    var bitmap by remember(handle.url) { mutableStateOf<Bitmap?>(null) }
    var error by remember(handle.url) { mutableStateOf<String?>(null) }
    var loading by remember(handle.url) { mutableStateOf(true) }

    LaunchedEffect(handle.url, retryKey) {
        bitmap = null
        error = null
        loading = true
        val bytes = try {
            withContext(Dispatchers.IO) {
                handle.okHttpClient.newCall(Request.Builder().url(handle.url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Camera returned HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Camera returned an empty photo")
                    body.bytes()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            error = failure.message ?: "Could not load the photo."
            loading = false
            return@LaunchedEffect
        }

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (decoded == null) {
            error = "The camera sent a photo this app could not decode."
        } else {
            bitmap = decoded
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(formatClipTimestamp(handle.clip))
                        Text(
                            handle.clip.name,
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
                contentAlignment = Alignment.Center,
            ) {
                when {
                    bitmap != null -> Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = handle.clip.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )

                    loading -> CircularProgressIndicator(color = Color.White)

                    else -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(24.dp),
                    ) {
                        Text(
                            "Could not show the photo",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                        Text(
                            error ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Button(
                            onClick = {
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
                    listOfNotNull(
                        handle.clip.resolution,
                        formatSize(handle.clip.sizeBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Loaded directly from the camera.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DownloadFooter(
                    downloadState = downloadState,
                    isVideo = false,
                    onDownload = onDownload,
                )
            }
        }
    }
}
