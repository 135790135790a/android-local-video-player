package com.example.videoplayer.ui.home

import android.content.Intent
import android.widget.Toast
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.videoplayer.data.RecentVideo
import com.example.videoplayer.util.toPlaybackTime
import com.example.videoplayer.util.toReadableSize

/**
 * 首页：未选择过视频时显示引导页；否则显示最近播放列表（最多 5 条）。
 *
 * 选择视频走 SAF 的 OpenDocument，不申请任何存储运行时权限；
 * 拿到 content:// Uri 后立刻 takePersistableUriPermission 持久化读权限。
 */
@Composable
fun HomeScreen(
    recentVideos: List<RecentVideo>,
    onPickVideo: (Uri) -> Unit,
    onPlayRecent: (RecentVideo) -> Unit,
    onRemoveRecent: (String) -> Unit,
    onClearRecent: () -> Unit
) {
    val context = LocalContext.current
    val pickVideo = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onPickVideo(uri)
        }
    }
    val launchPicker: () -> Unit = {
        // 极简 ROM / 定制系统可能没有 DocumentsUI，launch 会抛 ActivityNotFoundException
        runCatching { pickVideo.launch(arrayOf("video/*")) }
            .onFailure {
                Toast.makeText(
                    context,
                    "系统文件选择器不可用，无法选择视频",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = 760.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { Header(onPick = launchPicker) }

                if (recentVideos.isEmpty()) {
                    item { EmptyState(onPick = launchPicker) }
                } else {
                    item { RecentHeader(onClear = onClearRecent) }
                    items(items = recentVideos, key = { it.uri }) { video ->
                        RecentVideoRow(
                            video = video,
                            onPlay = { onPlayRecent(video) },
                            onRemove = { onRemoveRecent(video.uri) }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(4.dp))
                        FilledTonalButton(
                            onClick = launchPicker,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "选择其他视频")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "本地视频播放器",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "直接读取你选择的文件，不申请存储权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onPick) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = "选择视频",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun EmptyState(onPick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp)
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "还没有打开过视频",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "点击下方按钮，从系统文件选择器中挑一个视频。\n播放器只保留该文件的读取授权，不会扫描你的相册。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(28.dp))
        Button(
            onClick = onPick,
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "选择视频")
        }
    }
}

@Composable
private fun RecentHeader(onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "最近播放",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClear) {
            Text(text = "清空")
        }
    }
}

@Composable
private fun RecentVideoRow(
    video: RecentVideo,
    onPlay: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        RoundedCornerShape(12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = buildString {
                        append(video.sizeBytes.toReadableSize())
                        if (video.durationMs > 0L) {
                            append(" · ")
                            append(video.durationMs.toPlaybackTime())
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "从最近播放中移除 ${video.displayName}"
                )
            }
        }
    }
}
