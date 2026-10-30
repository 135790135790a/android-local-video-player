package com.example.videoplayer.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.videoplayer.data.db.VideoEntity
import com.example.videoplayer.util.Formatters

/** 视频详情：分辨率 / 帧率 / 码率 / 编码 / 时长 / 大小 / 来源 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoInfoSheet(
    video: VideoEntity,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = video.fileName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(10.dp))

            InfoRow("分辨率", "${video.resolutionText}（${video.resolutionLabel}）")
            InfoRow("帧率", Formatters.frameRate(video.frameRate))
            InfoRow("码率", Formatters.bitRate(video.bitRate))
            InfoRow("视频编码", video.videoCodec.ifBlank { "未知" })
            InfoRow("音频编码", video.audioCodec.ifBlank { "未知" })
            InfoRow("容器格式", video.mimeType.ifBlank { "未知" })
            InfoRow("时长", Formatters.duration(video.durationMs))
            InfoRow("文件大小", Formatters.bytes(video.size))
            InfoRow("导入时间", Formatters.dateTime(video.importedAt))
            InfoRow("最后播放", Formatters.dateTime(video.lastPlayedAt))
            InfoRow("播放次数", "${video.playCount} 次")

            if (video.isPlayable) {
                InfoRow("本地路径", video.localPath, maxLines = 2)
            } else {
                InfoRow("状态", "源文件已丢失（迁移时无法读取）")
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            Text(
                text = "关于清晰度",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "本地视频是单码率文件，本身不含多个清晰度档位，因此不支持自动清晰度切换。" +
                    "这里显示的「${video.resolutionLabel}」是文件编码时的实际分辨率。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onDismiss(); onPlay() },
                    enabled = video.isPlayable,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("播放", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = { onDismiss(); onDelete() }) {
                    Icon(Icons.Filled.Delete, contentDescription = null)
                    Text("删除", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, maxLines: Int = 1) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp)
        )
    }
}
