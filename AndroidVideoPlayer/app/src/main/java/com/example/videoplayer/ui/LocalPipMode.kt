package com.example.videoplayer.ui

import androidx.compose.runtime.compositionLocalOf

/**
 * 当前 Activity 是否处于画中画模式。
 *
 * 画中画窗口只有巴掌大，控制栏放进去会盖住画面，所以播放页在 PiP 里要整体隐藏控件。
 * 值由 `MainActivity.onPictureInPictureModeChanged` 写入，Compose 侧只读。
 */
val LocalPipMode = compositionLocalOf { false }
