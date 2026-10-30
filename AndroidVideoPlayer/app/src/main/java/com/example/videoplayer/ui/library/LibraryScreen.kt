package com.example.videoplayer.ui.library

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.videoplayer.util.AppImageLoader
import com.example.videoplayer.data.db.VideoEntity
import com.example.videoplayer.util.Formatters
import java.io.File

/**
 * 媒体库主页面：已入库视频的唯一播放入口。
 *
 * 顶部是容量统计与导入入口，下面是 3 列（横屏 5 列）网格。
 * 源文件已丢失的条目灰显但仍然显示，方便用户手动删除。
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    compact: Boolean,
    onOpenVideo: (VideoEntity) -> Unit,
    onPickFromGallery: () -> Unit,
    onPickFromSaf: () -> Unit,
    modifier: Modifier = Modifier
) {
    val videos by viewModel.videos.collectAsState()
    val usage by viewModel.usage.collectAsState()
    val importState by viewModel.importState.collectAsState()
    val message by viewModel.message.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    var infoVideo by remember { mutableStateOf<VideoEntity?>(null) }
    var importMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(text)
        viewModel.consumeMessage()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "我的媒体库",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新媒体库")
                }
                Box {
                    IconButton(onClick = { importMenuOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "导入视频")
                    }
                    DropdownMenu(
                        expanded = importMenuOpen,
                        onDismissRequest = { importMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("从相册导入") },
                            onClick = { importMenuOpen = false; onPickFromGallery() }
                        )
                        DropdownMenuItem(
                            text = { Text("从文件选择导入") },
                            onClick = { importMenuOpen = false; onPickFromSaf() }
                        )
                    }
                }
            }

            UsageCard(
                usage = usage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            ImportBanner(
                state = importState,
                onCancel = viewModel::cancelImport,
                onDismiss = viewModel::dismissImportResult
            )

            if (videos.isEmpty()) {
                EmptyLibrary(
                    onPickFromGallery = onPickFromGallery,
                    onPickFromSaf = onPickFromSaf,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (compact) 3 else 5),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(items = videos, key = { it.id }) { video ->
                        VideoTile(
                            video = video,
                            onPlay = {
                                if (video.isPlayable) {
                                    viewModel.markPlayed(video)
                                    onOpenVideo(video)
                                } else {
                                    infoVideo = video
                                }
                            },
                            onInfo = { infoVideo = video }
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
        )
    }

    infoVideo?.let { video ->
        VideoInfoSheet(
            video = video,
            onDismiss = { infoVideo = null },
            onPlay = {
                if (video.isPlayable) {
                    viewModel.markPlayed(video)
                    onOpenVideo(video)
                }
            },
            onDelete = { viewModel.deleteVideo(video) }
        )
    }
}

@Composable
private fun UsageCard(usage: LibraryUsage, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            StatItem("视频", "${usage.count} 个")
            StatItem("已占用", Formatters.bytes(usage.totalBytes))
            StatItem("可用空间", Formatters.bytes(usage.freeBytes))
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ImportBanner(
    state: ImportState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        ImportState.Idle -> Unit

        is ImportState.Running -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (state.total > 0) {
                            "导入中 ${state.index}/${state.total}"
                        } else {
                            "正在准备导入…"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) {
                        Text("取消", color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                if (state.fileName.isNotBlank()) {
                    Text(
                        text = state.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { (state.index.toFloat() / state.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "成功 ${state.done} · 失败 ${state.failed}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        is ImportState.Finished -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = buildString {
                        append("导入完成：成功 ${state.done}")
                        if (state.failed > 0) append("，失败 ${state.failed}")
                        if (state.skipped > 0) append("，跳过重复 ${state.skipped}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }

        is ImportState.Failed -> Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(
    onPickFromGallery: () -> Unit,
    onPickFromSaf: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "媒体库还是空的",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "导入的视频会复制到应用私有目录，导入后原文件删掉也能正常播放；卸载应用时一起清除。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onPickFromGallery, modifier = Modifier.fillMaxWidth()) {
            Text("从相册导入")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onPickFromSaf, modifier = Modifier.fillMaxWidth()) {
            Text("从文件选择导入")
        }
    }
}

@Composable
private fun VideoTile(
    video: VideoEntity,
    onPlay: () -> Unit,
    onInfo: () -> Unit
) {
    val context = LocalContext.current
    val request = remember(video.localPath) {
        ImageRequest.Builder(context)
            .data(File(video.localPath))
            .videoFrameMillis(1_000L)
            .crossfade(true)
            .build()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(
                    enabled = video.isPlayable,
                    onClickLabel = "播放${video.fileName}",
                    onClick = onPlay
                )
        ) {
            if (video.isPlayable) {
                AsyncImage(
                    model = request,
                    imageLoader = AppImageLoader.get(context),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 分辨率角标
            if (video.width > 0 && video.height > 0) {
                SurfaceBadge(
                    text = video.resolutionLabel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                )
            }

            // 时长角标
            if (video.durationMs > 0) {
                SurfaceBadge(
                    text = Formatters.duration(video.durationMs),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                )
            }

            // 详情按钮（右上角）
            IconButton(
                onClick = onInfo,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(34.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = "查看${video.fileName}的详细信息",
                    tint = Color.White,
                    modifier = Modifier.size(17.dp)
                )
            }

            if (!video.isPlayable) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.62f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "源文件已丢失",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = video.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = buildString {
                append(Formatters.bytes(video.size))
                if (video.videoCodec.isNotBlank()) {
                    append(" · ")
                    append(video.videoCodec)
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SurfaceBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.58f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White
        )
    }
}
