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
import androidx.media3.ui.CaptionStyleCompat
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
    /**
     * v1.0.5 起播放器归 PlaybackService 所有，UI 侧通过 MediaController 异步连接，
     * 连接完成前这里可能是 null（PlayerView 允许 setPlayer(null)，画面显示黑底即可）。
     */
    player: Player?,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setBackgroundColor(Color.BLACK)
                configureSubtitleView()
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

/**
 * 外挂字幕的显示样式。
 *
 * PlayerView 自带一个 SubtitleView，ExoPlayer 解析出的 cue 会直接送到这里渲染，
 * 不需要我们自己画字幕。这里只做三件事：白字 + 半透明黑底 + 描边保证任意画面下可读；
 * 底部留 12% 空白，避免字幕被 Compose 绘制的进度条压住。
 */
@OptIn(UnstableApi::class)
private fun PlayerView.configureSubtitleView() {
    val subtitle = subtitleView ?: return
    subtitle.setStyle(
        CaptionStyleCompat(
            Color.WHITE,
            android.graphics.Color.argb(0xB3, 0x00, 0x00, 0x00),
            Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            Color.BLACK,
            null
        )
    )
    subtitle.setFractionalTextSize(0.053f)
    subtitle.setBottomPaddingFraction(0.12f)
}
