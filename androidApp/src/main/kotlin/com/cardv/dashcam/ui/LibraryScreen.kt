package com.cardv.dashcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardv.dashcam.MainUiState
import com.cardv.dashcam.download.DownloadPhase
import com.cardv.dashcam.download.DownloadUiState
import com.cardv.shared.library.ClipDayGroup
import com.cardv.shared.library.ClipSort
import com.cardv.shared.model.Clip
import com.cardv.shared.model.Folder

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
    onOpenLive: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        LibraryHeader(
            state = state,
            onRefresh = onRefresh,
            onDisconnect = onDisconnect,
            onOpenLive = onOpenLive,
            onOpenControls = onOpenControls,
            onOpenSettings = onOpenSettings,
        )
        FolderPills(state = state, onSelectFolder = onSelectFolder)
        LibraryToolbar(
            state = state,
            onSort = onSort,
            onToggleGrouping = onToggleGrouping,
        )

        val folderError = state.folderError
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                state.isLoadingFolder && state.clips.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = colors.primary)
                }

                folderError != null && state.clips.isEmpty() -> LibraryMessage(
                    icon = Icons.Rounded.ErrorOutline,
                    title = "Can't load this folder",
                    body = folderError,
                    action = "Try again",
                    onAction = onRefresh,
                )

                state.clips.isEmpty() -> LibraryMessage(
                    icon = if (state.folder == Folder.PHOTO) {
                        Icons.Rounded.PhotoCamera
                    } else {
                        Icons.Rounded.Videocam
                    },
                    title = if (state.folder == Folder.PHOTO) "No snapshots yet" else "No clips yet",
                    body = if (state.folder == Folder.PHOTO) {
                        "Snapshots from the dashcam will appear here."
                    } else {
                        "New recordings will appear here after the camera saves them."
                    },
                )

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryHeader(
    state: MainUiState,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenLive: () -> Unit,
    onOpenControls: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var moreOpen by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = buildAnnotatedString {
                        append("Car")
                        withStyle(SpanStyle(color = colors.primary)) { append("DV") }
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.onBackground,
                )
                Text(
                    text = "DASHCAM LIBRARY",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    letterSpacing = 2.sp,
                )
            }
            HeaderAction(Icons.Rounded.Videocam, "Live view", onOpenLive)
            Spacer(Modifier.size(6.dp))
            HeaderAction(Icons.Rounded.Tune, "Camera controls", onOpenControls)
            Spacer(Modifier.size(6.dp))
            HeaderAction(Icons.Rounded.Settings, "Settings", onOpenSettings)
            Spacer(Modifier.size(6.dp))
            Box {
                HeaderAction(Icons.Rounded.MoreVert, "More", { moreOpen = true })
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Refresh library") },
                        onClick = {
                            moreOpen = false
                            onRefresh()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Disconnect") },
                        onClick = {
                            moreOpen = false
                            onDisconnect()
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusChip(Icons.Rounded.Wifi, "Connected", accent = true)
            if (state.isRecording) {
                StatusChip(Icons.Rounded.Videocam, "Recording", accent = true)
            }
            if (state.clockMessage?.startsWith("Camera clock synced") == true) {
                StatusChip(Icons.Rounded.CheckCircle, "Clock set")
            }
            val storage = buildString {
                state.sdStatus?.let { append("SD $it") }
                val free = state.freeMb
                if (free != null) {
                    if (isNotEmpty()) append(" · ")
                    append("${formatSize(free * 1024L * 1024L)} free")
                }
            }
            if (storage.isNotEmpty()) {
                StatusChip(Icons.Rounded.SdCard, storage)
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(colors.surfaceVariant.copy(alpha = 0.7f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StatusChip(icon: ImageVector, text: String, accent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = if (accent) colors.primary.copy(alpha = 0.14f) else colors.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accent) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (accent) colors.primary else colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FolderPills(state: MainUiState, onSelectFolder: (Folder) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Folder.entries.forEach { folder ->
            val selected = state.folder == folder
            val count = state.count(folder)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) colors.primary else colors.surfaceVariant.copy(alpha = 0.7f))
                    .clickable { onSelectFolder(folder) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = folderIcon(folder),
                    contentDescription = null,
                    tint = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = folderTitle(folder),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
                if (count != null) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) {
                            colors.onPrimary.copy(alpha = 0.65f)
                        } else {
                            colors.onSurfaceVariant.copy(alpha = 0.65f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryToolbar(
    state: MainUiState,
    onSort: (ClipSort) -> Unit,
    onToggleGrouping: () -> Unit,
) {
    var sortMenuOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val count = state.clips.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$count " + if (state.folder == Folder.PHOTO) "snapshots" else "clips",
            style = MaterialTheme.typography.titleMedium,
            color = colors.onBackground,
            modifier = Modifier.weight(1f),
        )

        Box {
            ToolbarPill(Icons.AutoMirrored.Rounded.Sort, sortLabel(state.sort)) { sortMenuOpen = true }
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
        Spacer(Modifier.size(8.dp))
        ToolbarPill(
            icon = Icons.Rounded.CalendarToday,
            label = if (state.groupByDay) "By day" else "Flat",
            onClick = onToggleGrouping,
        )
    }
}

@Composable
private fun ToolbarPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceVariant.copy(alpha = 0.65f))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
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
        columns = GridCells.Adaptive(minSize = 160.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        groups.forEach { group ->
            if (showHeaders) {
                item(key = "header-${group.date}", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = formatDayLabel(group.date).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            items(group.clips, key = { it.path }) { clip ->
                ClipTile(
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
private fun ClipTile(
    clip: Clip,
    state: MainUiState,
    onOpen: (Clip) -> Unit,
    onThumbnail: (Clip) -> Unit,
    onDownload: (Clip) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val download = state.downloads[clip.path]
    val isRecording = clip.name == state.currentRecordingName
    val bitmap = state.thumbnails[clip.path]

    LaunchedEffect(clip.path) { onThumbnail(clip) }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surfaceVariant)
                .clickable { onOpen(clip) },
        ) {
            if (bitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (clip.isVideo) {
                            Icons.Rounded.Videocam
                        } else {
                            Icons.Rounded.PhotoCamera
                        },
                        contentDescription = null,
                        tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)),
                            startY = 250f,
                        ),
                    ),
            )

            if (clip.isVideo && !isRecording) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            if (isRecording) {
                Text(
                    text = "REC",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.error,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            val duration = formatDuration(clip.durationSeconds)
            if (clip.isVideo && duration != null) {
                Text(
                    text = duration,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            if (!isRecording) {
                DownloadAction(
                    download = download,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                    onDownload = { onDownload(clip) },
                )
            }

            if (download?.phase == DownloadPhase.DOWNLOADING) {
                val fraction = download.fraction
                if (fraction == null) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                        color = colors.primary,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp),
                        color = colors.primary,
                        trackColor = Color.Transparent,
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = formatClipTimestamp(clip),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(
                clip.resolution,
                if (clip.isVideo) null else "Snapshot",
                formatSize(clip.sizeBytes),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (download?.phase == DownloadPhase.FAILED) {
            Text(
                text = download.error ?: "Download failed · tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = colors.error,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DownloadAction(
    download: DownloadUiState?,
    modifier: Modifier = Modifier,
    onDownload: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val base = modifier
        .size(34.dp)
        .clip(CircleShape)
        .background(Color.Black.copy(alpha = 0.45f))

    when (download?.phase) {
        null, DownloadPhase.CANCELLED, DownloadPhase.FAILED -> Box(
            modifier = base.clickable(onClick = onDownload),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (download?.phase == DownloadPhase.FAILED) {
                    Icons.Rounded.Refresh
                } else {
                    Icons.Rounded.Download
                },
                contentDescription = if (download?.phase == DownloadPhase.FAILED) {
                    "Retry download"
                } else {
                    "Download"
                },
                tint = if (download?.phase == DownloadPhase.FAILED) colors.error else Color.White,
                modifier = Modifier.size(18.dp),
            )
        }

        DownloadPhase.QUEUED -> Box(modifier = base, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = "Queued",
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp),
            )
        }

        DownloadPhase.DOWNLOADING, DownloadPhase.EXPORTING -> Box(
            modifier = base,
            contentAlignment = Alignment.Center,
        ) {
            val fraction = download?.fraction
            if (fraction == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    color = colors.primary,
                    strokeWidth = 2.5.dp,
                )
            } else {
                CircularProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.size(26.dp),
                    color = colors.primary,
                    strokeWidth = 2.5.dp,
                    trackColor = Color.White.copy(alpha = 0.25f),
                )
            }
        }

        DownloadPhase.SAVED -> Box(modifier = base, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = "Saved",
                tint = colors.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LibraryMessage(
    icon: ImageVector,
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(colors.surfaceVariant.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.onBackground,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (action != null && onAction != null) {
                Surface(
                    onClick = onAction,
                    shape = RoundedCornerShape(50),
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                ) {
                    Text(
                        text = action,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

private fun folderIcon(folder: Folder): ImageVector = when (folder) {
    Folder.DCIM -> Icons.Rounded.Videocam
    Folder.SOS -> Icons.Rounded.Warning
    Folder.LOCK -> Icons.Rounded.Lock
    Folder.PHOTO -> Icons.Rounded.PhotoCamera
}

private fun folderTitle(folder: Folder): String = when (folder) {
    Folder.DCIM -> "Loop"
    Folder.SOS -> "SOS"
    Folder.LOCK -> "Lock"
    Folder.PHOTO -> "Photos"
}

private fun sortLabel(sort: ClipSort): String = when (sort) {
    ClipSort.NEWEST_FIRST -> "Newest"
    ClipSort.OLDEST_FIRST -> "Oldest"
    ClipSort.NAME -> "Name"
}
