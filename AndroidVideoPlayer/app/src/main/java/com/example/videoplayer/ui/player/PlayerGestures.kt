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
 * - 左右滑动：松手后快进 / 快退 10 秒
 * - 上下滑动：按格调节系统媒体音量
 */
@Composable
fun GestureLayer(
    volumePercent: Int,
    onSingleTap: () -> Unit,
    onDoubleTap: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onVolumeStep: (Int) -> Unit,
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
                    var volumeAccum = 0f
                    detectDragGestures(
                        onDragStart = {
                            totalX = 0f
                            totalY = 0f
                            volumeAccum = 0f
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
                                volumeAccum += dragAmount.y
                                val steps = (volumeAccum / stepPx).toInt()
                                if (steps != 0) {
                                    // 手指向下 -> 音量减小
                                    onVolumeStep(-steps)
                                    volumeAccum -= steps * stepPx
                                }
                                hint = "音量 $volumePercent%"
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onSingleTap() },
                        onDoubleTap = { onDoubleTap() }
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
