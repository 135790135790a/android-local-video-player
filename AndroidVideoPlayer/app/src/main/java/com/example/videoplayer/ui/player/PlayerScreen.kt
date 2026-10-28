package com.example.videoplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.videoplayer.player.PlayerViewModel
import kotlinx.coroutines.delay

private const val AUTO_HIDE_DELAY_MS = 3_000L

/**
 * 播放页。
 *
 * 进入即沉浸式全屏（隐藏状态栏/导航栏 + 边到边绘制），横屏按钮切换
 * `requestedOrientation`，退出时恢复系统栏与屏幕方向。
 *
 * 单击画面：控件可见时切换播放/暂停，控件隐藏时先唤出控件。
 * 双击画面：切换播放/暂停（与单击共用 detectTapGestures，系统会自动延迟单击回调）。
 * 播放中 3 秒无操作自动隐藏控件。
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val compact = configuration.screenWidthDp < 600

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionKey by remember { mutableIntStateOf(0) }

    fun poke() {
        controlsVisible = true
        interactionKey++
    }

    // 沉浸式全屏 + 退出时恢复
    DisposableEffect(activity) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // 播放中保持屏幕常亮
    DisposableEffect(activity, state.isPlaying) {
        val window = activity?.window
        if (state.isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // 播放中且控件可见 -> 3 秒后自动隐藏
    LaunchedEffect(controlsVisible, state.isPlaying, interactionKey) {
        if (controlsVisible && state.isPlaying) {
            delay(AUTO_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }

    BackHandler {
        viewModel.pause()
        onBack()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        VideoSurface(player = viewModel.player, modifier = Modifier.fillMaxSize())

        GestureLayer(
            volumePercent = state.volumePercent,
            onSingleTap = {
                if (controlsVisible) {
                    viewModel.togglePlayPause()
                }
                poke()
            },
            onDoubleTap = {
                viewModel.togglePlayPause()
                poke()
            },
            onSeekBy = { deltaMs ->
                viewModel.seekBy(deltaMs)
                poke()
            },
            onVolumeStep = { steps ->
                viewModel.adjustVolumeBy(steps)
                poke()
            },
            modifier = Modifier.fillMaxSize()
        )

        if (state.isBuffering) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PlayerTopBar(
                    title = state.videoName.ifBlank { "本地视频" },
                    onBack = {
                        viewModel.pause()
                        onBack()
                    },
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                PlayerBottomBar(
                    state = state,
                    compact = compact,
                    isLandscape = isLandscape,
                    onPlayPause = viewModel::togglePlayPause,
                    onVolumeClick = viewModel::cycleVolume,
                    onSpeedSelected = viewModel::setSpeed,
                    onToggleFullscreen = {
                        activity?.requestedOrientation = if (isLandscape) {
                            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        } else {
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        }
                        poke()
                    },
                    onSeek = viewModel::seekTo,
                    onInteract = {
                        controlsVisible = true
                        interactionKey++
                    },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                if (!state.isPlaying && !state.isBuffering) {
                    CenterPlayButton(
                        visible = true,
                        onPlay = {
                            viewModel.play()
                            poke()
                        }
                    )
                }
            }
        }

        state.errorMessage?.let { message ->
            PlayerErrorDialog(
                message = message,
                detail = state.errorDetail,
                onRetry = {
                    viewModel.retry()
                    poke()
                },
                onDismiss = viewModel::dismissError
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
