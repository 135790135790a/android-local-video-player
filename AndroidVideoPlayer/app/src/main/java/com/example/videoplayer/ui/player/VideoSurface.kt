package com.example.videoplayer.ui.player

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.videoplayer.R

/**
 * 视频画面。PlayerView 只负责渲染，不显示自带控制条（use_controller=false），
 * 所有控件都由 Compose 绘制。
 */
@SuppressLint("InflateParams")
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(
    player: Player,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(Color.BLACK)
                this.player = player
            }
        },
        update = { view ->
            view.player = player
            // 画面比例：默认 FIT，避免拉伸变形
            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        },
        onRelease = { view ->
            view.player = null
        }
    )
}
