package com.example.videoplayer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.videoplayer.player.PlayerViewModel
import com.example.videoplayer.ui.home.HomeScreen
import com.example.videoplayer.ui.player.PlayerScreen

/**
 * 极简导航：首页 <-> 播放页。
 *
 * 不引入 navigation-compose，用 rememberSaveable 记住当前页面；
 * ViewModel 挂在 Activity 上，两个页面共用同一个 ExoPlayer 实例。
 */
@Composable
fun AppRoot(viewModel: PlayerViewModel) {
    val recentVideos by viewModel.recentVideos.collectAsState()
    val state by viewModel.uiState.collectAsState()

    var showPlayer by rememberSaveable { mutableStateOf(false) }

    // 进程被系统回收后恢复：Activity 重建但 ViewModel 是新的，播放器里没有媒体，
    // 这时直接回首页，避免停在空白的播放页。
    LaunchedEffect(showPlayer, state.hasVideo) {
        if (showPlayer && !state.hasVideo) {
            showPlayer = false
        }
    }

    if (showPlayer) {
        PlayerScreen(
            viewModel = viewModel,
            onBack = { showPlayer = false }
        )
    } else {
        HomeScreen(
            recentVideos = recentVideos,
            onPickVideo = { uri ->
                viewModel.openVideo(uri)
                showPlayer = true
            },
            onPlayRecent = { video ->
                viewModel.openVideo(Uri.parse(video.uri))
                showPlayer = true
            },
            onRemoveRecent = viewModel::removeRecent,
            onClearRecent = viewModel::clearRecent
        )
    }
}
