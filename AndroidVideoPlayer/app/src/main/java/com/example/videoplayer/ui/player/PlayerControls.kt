package com.example.videoplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.videoplayer.R
import com.example.videoplayer.player.PlayerUiState
import com.example.videoplayer.player.PlayerViewModel
import com.example.videoplayer.player.VolumeLevel
import com.example.videoplayer.util.toPlaybackTime

/* ------------------------------- 顶部标题栏 ------------------------------- */

@Composable
fun PlayerTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回首页",
                tint = Color.White
            )
        }
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 16.dp)
        )
    }
}

/* ------------------------------- 底部控制栏 ------------------------------- */

@Composable
fun PlayerBottomBar(
    state: PlayerUiState,
    compact: Boolean,
    isLandscape: Boolean,
    onPlayPause: () -> Unit,
    onVolumeClick: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onToggleFullscreen: () -> Unit,
    onSeek: (Long) -> Unit,
    onInteract: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f))
                )
            )
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)
    ) {
        ProgressRow(state = state, onSeek = onSeek, onInteract = onInteract)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onPlayPause(); onInteract() }) {
                if (state.isPlaying) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = "暂停",
                        tint = Color.White
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "播放",
                        tint = Color.White
                    )
                }
            }

            VolumeButton(
                level = state.volumeLevel,
                onClick = { onVolumeClick(); onInteract() }
            )

            Spacer(modifier = Modifier.weight(1f))

            SpeedButton(
                speed = state.speed,
                compact = compact,
                onSpeedSelected = { onSpeedSelected(it); onInteract() }
            )

            IconButton(onClick = { onToggleFullscreen(); onInteract() }) {
                Icon(
                    painter = painterResource(
                        if (isLandscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
                    ),
                    contentDescription = if (isLandscape) "退出全屏" else "全屏播放",
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun ProgressRow(
    state: PlayerUiState,
    onSeek: (Long) -> Unit,
    onInteract: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    val duration = state.durationMs.coerceAtLeast(0L)
    val sliderMax = duration.coerceAtLeast(1L).toFloat()
    val current = if (dragging) dragValue else state.positionMs.toFloat().coerceIn(0f, sliderMax)
    val shownPosition = if (dragging) dragValue.toLong() else state.positionMs
    val bufferedFraction =
        if (duration > 0L) (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f

    val timeStyle = MaterialTheme.typography.labelMedium

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = shownPosition.toPlaybackTime(),
            color = Color.White,
            style = timeStyle,
            modifier = Modifier.widthIn(min = 46.dp)
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Slider(
                value = current,
                onValueChange = {
                    dragging = true
                    dragValue = it
                    onInteract()
                },
                onValueChangeFinished = {
                    dragging = false
                    onSeek(dragValue.toLong())
                    onInteract()
                },
                valueRange = 0f..sliderMax,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.24f)
                )
            )

            // 已缓冲进度：叠加在轨道上的细线，不拦截触摸
            if (bufferedFraction > 0f) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(horizontal = 10.dp)
                ) {
                    drawRect(
                        color = Color.White.copy(alpha = 0.45f),
                        size = Size(size.width * bufferedFraction, size.height)
                    )
                }
            }
        }

        Text(
            text = duration.toPlaybackTime(),
            color = Color.White.copy(alpha = 0.7f),
            style = timeStyle,
            modifier = Modifier.widthIn(min = 46.dp)
        )
    }
}

@Composable
private fun VolumeButton(level: VolumeLevel, onClick: () -> Unit) {
    val iconRes = when (level) {
        VolumeLevel.MUTE -> R.drawable.ic_volume_off
        VolumeLevel.LOW -> R.drawable.ic_volume_down
        VolumeLevel.HIGH -> R.drawable.ic_volume_up
    }
    val label = when (level) {
        VolumeLevel.MUTE -> "当前静音，点击切换音量"
        VolumeLevel.LOW -> "当前低音量，点击切换音量"
        VolumeLevel.HIGH -> "当前高音量，点击切换音量"
    }
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = Color.White
        )
    }
}

@Composable
private fun SpeedButton(
    speed: Float,
    compact: Boolean,
    onSpeedSelected: (Float) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = speedLabel(speed)

    Box {
        if (compact) {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_speed),
                    contentDescription = "播放速度 $label，点击调整",
                    tint = Color.White
                )
            }
        } else {
            TextButton(onClick = { expanded = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_speed),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(4.dp))
                Text(text = label, color = Color.White)
            }
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PlayerViewModel.SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(speedLabel(option)) },
                    onClick = {
                        expanded = false
                        onSpeedSelected(option)
                    },
                    trailingIcon = {
                        if (option == speed) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = "当前选择",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                )
            }
        }
    }
}

private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

/* -------------------------------- 中央按钮 -------------------------------- */

@Composable
fun CenterPlayButton(visible: Boolean, onPlay: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "继续播放",
                    modifier = Modifier.size(46.dp)
                )
            }
        }
    }
}

/* -------------------------------- 错误弹窗 -------------------------------- */

@Composable
fun PlayerErrorDialog(
    message: String,
    detail: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "无法播放该视频") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = message)
                if (!detail.isNullOrBlank()) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(text = "重试") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "关闭") }
        }
    )
}
