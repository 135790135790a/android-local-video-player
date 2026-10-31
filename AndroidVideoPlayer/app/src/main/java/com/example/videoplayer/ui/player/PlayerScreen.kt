package com.example.videoplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.videoplayer.player.PlayerViewModel
import com.example.videoplayer.player.VideoSource
import kotlinx.coroutines.delay

private const val AUTO_HIDE_DELAY_MS = 3_000L

/** 预检通常几十毫秒就结束，低于这个时长的 loading 会一闪而过，反而干扰视线。 */
private const val CHECKING_INDICATOR_DELAY_MS = 200L

/** 从 content:// 取文件名，取不到就退回路径末段，保证按钮/状态里总有东西可显示。 */
private fun querySubtitleName(context: Context, uri: Uri): String =
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment ?: "外挂字幕"

/**
 * 播放页。
 *
 * 进入即沉浸式全屏（隐藏状态栏/导航栏 + 边到边绘制），横屏按钮切换
 * `requestedOrientation`，退出时恢复系统栏与屏幕方向。
 *
 * 单击画面：控件可见时切换播放/暂停，控件隐藏时先唤出控件。
 * 双击画面：切换播放/暂停（与单击共用 detectTapGestures，系统会自动延迟单击回调）。
 * 播放中 3 秒无操作自动隐藏控件。
 *
 * 三种打断状态：
 * - 正在预检（isChecking）-> 超过 200ms 才显示「正在检查视频」的 loading
 * - 预检判定不可播（preflightBlock）-> 弹窗说明原因，可「仍要尝试播放」
 * - 实际播放失败（errorMessage）-> 全屏错误页，带重试与返回相册
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onBackToGallery: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val compact = configuration.screenWidthDp < 600

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionKey by remember { mutableIntStateOf(0) }
    var showCheckingIndicator by remember { mutableStateOf(false) }

    fun poke() {
        controlsVisible = true
        interactionKey++
    }

    // ---- 外挂字幕：选文件 -> 持久化授权 -> 交给 ViewModel 挂到当前 MediaItem ----
    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.attachSubtitle(uri, querySubtitleName(context, uri))
            poke()
        }
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

    // 预检 loading：200ms 内结束就不显示，避免闪一下
    LaunchedEffect(state.isChecking) {
        if (state.isChecking) {
            delay(CHECKING_INDICATOR_DELAY_MS)
            showCheckingIndicator = true
        } else {
            showCheckingIndicator = false
        }
    }

    BackHandler {
        if (state.preflightBlock != null) {
            viewModel.dismissPreflightBlock()
        }
        viewModel.pause()
        onBack()
    }

    val overlayOpen = state.errorMessage != null || state.preflightBlock != null
    val barsVisible = controlsVisible && !overlayOpen

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        VideoSurface(player = viewModel.player, modifier = Modifier.fillMaxSize())

        // 错误页 / 预检弹窗打开时不响应手势，避免误触
        if (!overlayOpen) {
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
        }

        if (state.isBuffering) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
            )
        }

        if (showCheckingIndicator && !overlayOpen) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "正在检查视频…",
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        AnimatedVisibility(
            visible = barsVisible,
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
                    onSubtitleClick = { subtitlePicker.launch(arrayOf("*/*")) },
                    onSubtitlePick = { subtitlePicker.launch(arrayOf("*/*")) },
                    onSubtitleClear = viewModel::detachSubtitle,
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

                if (!state.isPlaying && !state.isBuffering && !state.isChecking) {
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

        // ---- 预检拦截 ----
        state.preflightBlock?.let { block ->
            PreflightBlockDialog(
                reason = block.reason,
                detail = block.detail,
                onPlayAnyway = {
                    viewModel.playAnyway()
                    poke()
                },
                onDismiss = {
                    viewModel.dismissPreflightBlock()
                    onBack()
                }
            )
        }

        // ---- 实播失败 ----
        state.errorMessage?.let { message ->
            PlayerErrorOverlay(
                message = message,
                detail = state.errorDetail,
                showBackToGallery = state.source == VideoSource.GALLERY,
                onRetry = {
                    viewModel.retry()
                    poke()
                },
                onBackToGallery = {
                    viewModel.pause()
                    onBackToGallery()
                },
                onDismiss = viewModel::dismissError
            )
        }
    }
}

/**
 * 预检判定不可播时的说明弹窗。
 *
 * 不允许把用户堵死：一定要留「仍要尝试播放」，
 * 因为个别机型的 MediaCodec 清单和实际播放能力并不完全一致。
 */
@Composable
private fun PreflightBlockDialog(
    reason: String,
    detail: String,
    onPlayAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    MaterialTheme.shapes.extraLarge
                )
                .padding(24.dp)
        ) {
            Text(
                text = "这个视频可能无法播放",
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (detail.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onPlayAnyway) {
                    Text("仍要尝试播放")
                }
            }
        }
    }
}

/**
 * 实际播放失败时的错误页。
 *
 * 依据：预检通过不代表一定能播（机型软解能力、文件中途损坏、Uri 被删除）。
 * 这里必须给一条明确的出路，不能白屏。
 */
@Composable
private fun PlayerErrorOverlay(
    message: String,
    detail: String?,
    showBackToGallery: Boolean,
    onRetry: () -> Unit,
    onBackToGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 460.dp)
                    .padding(horizontal = 28.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "无法播放这个视频",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center
                )
                if (!detail.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(modifier = Modifier.height(28.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onRetry) {
                        Text(
                            text = "重试",
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (showBackToGallery) {
                        TextButton(onClick = onBackToGallery) {
                            Icon(
                                imageVector = Icons.Filled.List,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "返回相册",
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "关闭",
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
