package com.example.videoplayer.ui.gallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.example.videoplayer.data.GalleryVideo
import com.example.videoplayer.data.Playability
import com.example.videoplayer.util.findActivity
import com.example.videoplayer.util.toPlaybackTime

/**
 * 相册页。
 *
 * 权限状态由 ViewModel 用 checkSelfPermission 主动复查（进入页面、ON_RESUME 各一次），
 * 请求本身走 RequestMultiplePermissions，不使用已废弃的 onRequestPermissionsResult。
 *
 * 校验不通过的条目：网格里盖灰罩 + 「不支持」角标，点击弹对话框说明原因，
 * 对话框里给了「仍要尝试播放」让用户绕过预检。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    viewModel: GalleryViewModel,
    onPlay: (GalleryVideo, Boolean) -> Unit,
    onRequestSafPick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // Activity 自己不是 LifecycleOwner，ComponentActivity 才是；转一下才能监听 ON_RESUME
    val lifecycleOwner = activity as? LifecycleOwner
    val permissions = remember { GalleryViewModel.requiredPermissions() }

    // 被判定「不支持」但仍被点开的视频，存在这里等用户确认
    var blockedVideo by remember { mutableStateOf<GalleryVideo?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.READ_MEDIA_VIDEO] == true ||
            result[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        val partial = result[GalleryViewModel.PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED] == true &&
            !granted
        // 请求完还是没授权、且系统已经不给弹窗了，才认定永久拒绝
        val permanentlyDenied = !granted && !partial && activity != null &&
            permissions.all { !ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        viewModel.onPermissionResult(granted, partial, permanentlyDenied)
    }

    val requestPermission: () -> Unit = {
        viewModel.resetPermissionState()
        // 极少数精简 ROM 没有权限弹窗实现，launch 可能抛异常
        runCatching { permissionLauncher.launch(permissions) }
    }

    // 从系统设置页返回时复查一次，防止用户撤销授权后仍用旧状态查库
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionStatus()
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
    }

    // Coil 默认不会解视频帧，必须显式注册 VideoFrameDecoder
    val imageLoader = remember(context) {
        ImageLoader.Builder(context)
            .components { add(VideoFrameDecoder.Factory()) }
            .crossfade(true)
            .build()
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = if (isLandscape) 5 else 3

    Box(modifier = modifier.fillMaxSize()) {
        when (state.permission) {
            GalleryPermission.CHECKING -> LoadingCentered("正在检查相册权限…")

            GalleryPermission.DENIED,
            GalleryPermission.PERMANENTLY_DENIED -> PermissionGuide(
                permanentlyDenied = state.permission == GalleryPermission.PERMANENTLY_DENIED,
                onRequest = requestPermission,
                onOpenSettings = { openAppSettings(context) },
                onPickViaSaf = onRequestSafPick
            )

            GalleryPermission.GRANTED,
            GalleryPermission.PARTIAL -> GalleryContent(
                state = state,
                columns = columns,
                imageLoader = imageLoader,
                onRefresh = { viewModel.refresh(showSpinner = false) },
                onManageAccess = requestPermission,
                onItemClick = { item ->
                    val verdict = item.verdict
                    if (verdict is Playability.Unsupported) {
                        blockedVideo = item.video
                    } else {
                        onPlay(item.video, false)
                    }
                }
            )
        }
    }

    blockedVideo?.let { video ->
        val verdict = viewModel.verdictFor(video)
        if (verdict is Playability.Unsupported) {
            UnsupportedDialog(
                reason = verdict.reason,
                detail = verdict.detail,
                onPlayAnyway = {
                    blockedVideo = null
                    onPlay(video, true)
                },
                onDismiss = { blockedVideo = null }
            )
        } else {
            blockedVideo = null
        }
    }
}

// ---------------------------------------------------------------------------
// 列表
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryContent(
    state: GalleryUiState,
    columns: Int,
    imageLoader: ImageLoader,
    onRefresh: () -> Unit,
    onManageAccess: () -> Unit,
    onItemClick: (GalleryItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.permission == GalleryPermission.PARTIAL) {
            PartialAccessBanner(onManageAccess = onManageAccess)
        }

        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            if (state.items.isEmpty() && !state.isLoading) {
                EmptyGallery()
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = state.items,
                        key = { it.video.id }
                    ) { item ->
                        VideoTile(
                            item = item,
                            imageLoader = imageLoader,
                            onClick = { onItemClick(item) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoTile(
    item: GalleryItem,
    imageLoader: ImageLoader,
    onClick: () -> Unit
) {
    val video = item.video
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(video.uri)
                .videoFrameMillis(1_000L)
                .crossfade(true)
                .build(),
            contentDescription = "${video.displayName} 的缩略图",
            imageLoader = imageLoader,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // 底部渐变压暗，保证文件名可读
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(46.dp)
                .background(Color.Black.copy(alpha = 0.42f))
        )

        Text(
            text = video.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .fillMaxWidth(0.72f)
        )

        // 时长角标
        Surface(
            color = Color.Black.copy(alpha = 0.62f),
            shape = RoundedCornerShape(5.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
        ) {
            Text(
                text = video.durationMs.toPlaybackTime(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }

        if (item.isUnsupported) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.58f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "不支持",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "点击查看原因",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }

        if (!item.isUnsupported && item.verdict == null && item.checking) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

    }
}

@Composable
private fun PartialAccessBanner(onManageAccess: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "你已授权部分视频 · 管理可访问的相册",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onManageAccess) {
                Text(text = "管理")
            }
        }
    }
}

@Composable
private fun EmptyGallery() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "相册里还没有视频",
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "拍一段视频，或者把视频拷到手机的 DCIM / Movies 目录后再下拉刷新。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoadingCentered(text: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------------------
// 权限引导
// ---------------------------------------------------------------------------

@Composable
private fun PermissionGuide(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickViaSaf: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(92.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(42.dp)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = if (permanentlyDenied) "相册权限已被拒绝" else "需要相册访问权限",
            style = MaterialTheme.typography.titleLarge
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = if (permanentlyDenied) {
                "系统已经不再允许弹出授权窗口。请到「设置 → 应用 → 本地视频播放器 → 权限」里手动打开视频访问权限。"
            } else {
                "授权后可以直接浏览相册里的视频。如果你不想授权，也可以用下面的「文件选择器」逐个挑视频，这种方式不需要任何权限。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        if (permanentlyDenied) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "去设置")
            }
            Spacer(modifier = Modifier.height(10.dp))
            FilledTonalButton(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(text = "再试一次")
            }
        } else {
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
                Text(text = "允许访问相册")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        TextButton(onClick = onPickViaSaf, modifier = Modifier.fillMaxWidth()) {
            Text(text = "改用文件选择器（不需要权限）")
        }
    }
}

@Composable
private fun UnsupportedDialog(
    reason: String,
    detail: String,
    onPlayAnyway: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text(text = "这个视频可能播不了") },
        text = {
            Column {
                Text(text = reason, style = MaterialTheme.typography.bodyMedium)
                if (detail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onPlayAnyway) {
                Text(text = "仍要尝试播放")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    )
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}
