package com.cardv.dashcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cardv.dashcam.MainUiState
import com.cardv.dashcam.download.DownloadPhase
import com.cardv.dashcam.download.DownloadUiState
import com.cardv.shared.library.ClipDayGroup
import com.cardv.shared.library.ClipSort
import com.cardv.shared.model.Clip
import com.cardv.shared.model.Folder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: MainUiState,
    onSelectFolder: (Folder) -> Unit,
    onSort: (ClipSort) -> Unit,
    onToggleGrouping: () -> Unit,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onOpen: (Clip) -> Unit,
    onThumbnail: (Clip) -> Unit,
    onDownload: (Clip) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Library")
                        Text(
                            text = statusLine(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onRefresh) { Text("Refresh") }
                    TextButton(onClick = onDisconnect) { Text("Disconnect") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            FolderTabs(state = state, onSelectFolder = onSelectFolder)
            LibraryControls(
                state = state,
                onSort = onSort,
                onToggleGrouping = onToggleGrouping,
                onRefresh = onRefresh,
            )
            state.clockMessage?.let { clockMessage ->
                Text(
                    text = clockMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if ("not updated" in clockMessage) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            val folderError = state.folderError
            when {
                state.isLoadingFolder && state.clips.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                folderError != null && state.clips.isEmpty() -> ErrorState(
                    message = folderError,
                    onRetry = onRefresh,
                )

                state.clips.isEmpty() -> EmptyState(state.folder)

                state.groupByDay -> ClipGrid(
                    groups = state.groups,
                    state = state,
                    onOpen = onOpen,
                    onThumbnail = onThumbnail,
                    onDownload = onDownload,
                )

                else -> ClipGrid(
                    groups = listOf(ClipDayGroup(date = null, clips = state.sortedClips)),
                    state = state,
                    onOpen = onOpen,
                    onThumbnail = onThumbnail,
                    onDownload = onDownload,
                    showHeaders = false,
                )
            }
        }
    }
}

@Composable
private fun statusLine(state: MainUiState): String = buildString {
    val firmware = state.firmware?.substringBefore(';')?.takeIf { it.isNotBlank() }
    append(firmware ?: "dashcam")
    if (state.sdStatus != null) append(" · SD ${state.sdStatus}")
    val free = state.freeMb
    val total = state.totalMb
    if (free != null && total != null) {
        append(" · ${formatSize(free * 1024L * 1024L)} free of ${formatSize(total * 1024L * 1024L)}")
    }
}

@Composable
private fun FolderTabs(state: MainUiState, onSelectFolder: (Folder) -> Unit) {
    val selectedIndex = Folder.entries.indexOf(state.folder)
    PrimaryTabRow(selectedTabIndex = selectedIndex) {
        Folder.entries.forEach { folder ->
            val count = state.count(folder)
            Tab(
                selected = state.folder == folder,
                onClick = { onSelectFolder(folder) },
                text = {
                    Text(
                        text = folderTitle(folder) + (count?.let { " $it" } ?: ""),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Composable
private fun LibraryControls(
    state: MainUiState,
    onSort: (ClipSort) -> Unit,
    onToggleGrouping: () -> Unit,
    onRefresh: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            TextButton(onClick = { sortMenuOpen = true }) {
                Text("Sort: ${sortLabel(state.sort)}")
            }
            DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                ClipSort.entries.forEach { sort ->
                    DropdownMenuItem(
                        text = { Text(sortLabel(sort)) },
                        onClick = {
                            sortMenuOpen = false
                            onSort(sort)
                        },
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleGrouping) {
                Text(if (state.groupByDay) "Grouped by day" else "No grouping")
            }
            TextButton(onClick = onRefresh) { Text("Reload") }
        }
    }
}

@Composable
private fun ClipGrid(
    groups: List<ClipDayGroup>,
    state: MainUiState,
    onOpen: (Clip) -> Unit,
    onThumbnail: (Clip) -> Unit,
    onDownload: (Clip) -> Unit,
    showHeaders: Boolean = true,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        groups.forEach { group ->
            if (showHeaders) {
                item(key = "header-${group.date}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = formatDayLabel(group.date),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                    )
                }
            }
            items(group.clips, key = { it.path }) { clip ->
                ClipCard(
                    clip = clip,
                    state = state,
                    onOpen = onOpen,
                    onThumbnail = onThumbnail,
                    onDownload = onDownload,
                )
            }
        }
    }
}

@Composable
private fun ClipCard(
    clip: Clip,
    state: MainUiState,
    onOpen: (Clip) -> Unit,
    onThumbnail: (Clip) -> Unit,
    onDownload: (Clip) -> Unit,
) {
    val download = state.downloads[clip.path]
    val isRecording = clip.name == state.currentRecordingName
    val bitmap = state.thumbnails[clip.path]

    LaunchedEffect(clip.path) { onThumbnail(clip) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onOpen(clip) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = clip.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                } else {
                    Text(
                        text = if (clip.isVideo) "VIDEO" else "PHOTO",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                if (clip.isVideo) {
                    val duration = formatDuration(clip.durationSeconds)
                    if (duration != null) {
                        Text(
                            text = duration,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.scrim)
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = formatClipTimestamp(clip),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        clip.resolution,
                        clip.fps?.let { "${it} fps" },
                        formatSize(clip.sizeBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                DownloadStatus(
                    download = download,
                    isRecording = isRecording,
                    isVideo = clip.isVideo,
                    onDownload = { onDownload(clip) },
                )
            }
        }
    }
}

@Composable
private fun DownloadStatus(
    download: DownloadUiState?,
    isRecording: Boolean,
    isVideo: Boolean,
    onDownload: () -> Unit,
) {
    if (isRecording) {
        Text(
            "Recording now — wait for the clip to close",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    when (download?.phase) {
        null, DownloadPhase.CANCELLED -> TextButton(onClick = onDownload) {
            Text(if (download?.phase == DownloadPhase.CANCELLED) "Resume" else "Download")
        }

        DownloadPhase.QUEUED -> Text(
            "Queued",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        DownloadPhase.DOWNLOADING -> Column {
            val fraction = download.fraction
            if (fraction == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = buildString {
                    append(formatSize(download.bytes))
                    if (download.totalBytes != null) append(" / ${formatSize(download.totalBytes)}")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        DownloadPhase.EXPORTING -> Text(
            "Saving to ${if (isVideo) "Movies" else "Pictures"}/CarDV…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        DownloadPhase.SAVED -> Text(
            "Saved to ${if (isVideo) "Movies" else "Pictures"}/CarDV",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
        )

        DownloadPhase.FAILED -> Column {
            Text(
                text = download.error ?: "Download failed",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onDownload) { Text("Retry") }
        }
    }
}

@Composable
private fun EmptyState(folder: Folder) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text("No ${folderTitle(folder).lowercase()} here", style = MaterialTheme.typography.titleMedium)
            Text(
                if (folder == Folder.PHOTO) {
                    "Snapshots appear here after you take one from the camera or the app's controls."
                } else {
                    "Nothing has been recorded in this folder yet."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text("Could not load this folder", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}

private fun folderTitle(folder: Folder): String = when (folder) {
    Folder.DCIM -> "Loop"
    Folder.SOS -> "SOS"
    Folder.LOCK -> "Locked"
    Folder.PHOTO -> "Photos"
}

private fun sortLabel(sort: ClipSort): String = when (sort) {
    ClipSort.NEWEST_FIRST -> "Newest"
    ClipSort.OLDEST_FIRST -> "Oldest"
    ClipSort.NAME -> "Name"
}
