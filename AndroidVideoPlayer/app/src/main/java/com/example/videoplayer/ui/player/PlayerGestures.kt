package com.example.videoplayer.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 播放画面上的手势层。
 *
 * - 单击：控件可见时播放/暂停，控件隐藏时先唤出控件
 * - 双击：播放/暂停
 * - 长按：在 2 倍速与原速之间切换（临时加速，不改设置里的默认倍速）
 * - 左右滑动：松手后快进 / 快退 10 秒
 * - 上下滑动：**右半屏调媒体音量，左半屏调屏幕亮度**（与主流播放器一致）
 */
@Composable
fun GestureLayer(
    volumePercent: Int,
    brightnessPercent: Int,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onVolumeStep: (Int) -> Unit,
    onBrightnessStep: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var hint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(800)
            hint = null
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    val stepPx = 26.dp.toPx()
                    val minDragPx = 48.dp.toPx()
                    var totalX = 0f
                    var totalY = 0f
                    var verticalAccum = 0f
                    // 手指落点在屏幕左半边 -> 调亮度；右半边 -> 调音量
                    var onLeftSide = false
                    // 本地累计值，避免依赖外部 state 更新带来的提示滞后
                    var localBrightness = brightnessPercent
                    var localVolume = volumePercent
                    detectDragGestures(
                        onDragStart = { offset ->
                            totalX = 0f
                            totalY = 0f
                            verticalAccum = 0f
                            onLeftSide = offset.x < size.width / 2f
                            localBrightness = brightnessPercent
                            localVolume = volumePercent
                        },
                        onDragEnd = {
                            if (abs(totalX) > abs(totalY) && abs(totalX) >= minDragPx) {
                                onSeekBy(if (totalX > 0) SEEK_STEP_MS else -SEEK_STEP_MS)
                            }
                            hint = null
                        },
                        onDragCancel = { hint = null },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            totalX += dragAmount.x
                            totalY += dragAmount.y
                            if (abs(totalX) >= abs(totalY)) {
                                hint = if (totalX >= 0f) "松开快进 10 秒" else "松开快退 10 秒"
                            } else {
                                verticalAccum += dragAmount.y
                                val steps = (verticalAccum / stepPx).toInt()
                                if (steps != 0) {
                                    // 手指向下 -> 减小
                                    if (onLeftSide) {
                                        onBrightnessStep(-steps)
                                        localBrightness = (localBrightness - steps).coerceIn(0, 100)
                                    } else {
                                        onVolumeStep(-steps)
                                        localVolume = (localVolume - steps * VOLUME_STEP_PERCENT).coerceIn(0, 100)
                                    }
                                    verticalAccum -= steps * stepPx
                                }
                                hint = if (onLeftSide) "亮度 $localBrightness%" else "音量 $localVolume%"
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onSingleTap() },
                        onDoubleTap = { onDoubleTap() },
                        onLongPress = { onLongPress() }
                    )
                }
        )

        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Text(
                text = hint.orEmpty(),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

private const val SEEK_STEP_MS = 10_000L

/** 音量按系统档位估算的百分比：一格大约 6%，与 [VolumeController] 的步进量保持同量级。 */
private const val VOLUME_STEP_PERCENT = 6
