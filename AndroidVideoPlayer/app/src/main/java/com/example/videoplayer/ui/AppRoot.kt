package com.example.videoplayer.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.videoplayer.player.PlayerViewModel
import com.example.videoplayer.ui.gallery.GalleryScreen
import com.example.videoplayer.ui.gallery.GalleryViewModel
import com.example.videoplayer.ui.library.LibraryScreen
import com.example.videoplayer.ui.library.LibraryViewModel
import com.example.videoplayer.ui.player.PlayerScreen

/** 首页的两个 Tab：媒体库是主入口，相册退化为导入来源。 */
private enum class HomeTab(val title: String) {
    LIBRARY("媒体库"),
    GALLERY("相册")
}

/**
 * 极简导航：首页（媒体库 / 相册）<-> 播放页。
 *
 * 不引入 navigation-compose，用 rememberSaveable 记住当前页面与 Tab；
 * PlayerViewModel 挂在 Activity 上，两个页面共用同一个 ExoPlayer 实例。
 *
 * 注意：第一个参数不能叫 `viewModel`，否则会遮蔽 `viewModel()` 这个 Composable 函数，
 * 导致 galleryViewModel / libraryViewModel 的默认值解析不出来。
 */
@Composable
fun AppRoot(
    playerViewModel: PlayerViewModel,
    galleryViewModel: GalleryViewModel = viewModel(),
    libraryViewModel: LibraryViewModel = viewModel()
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 600
    val state by playerViewModel.uiState.collectAsState()

    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(HomeTab.LIBRARY) }

    // 兜底入口：不申请存储权限，直接用系统文件选择器挑文件 —— 拿到后同样是入库，不直接播放
    val safPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            libraryViewModel.importUris(listOf(uri))
            tab = HomeTab.LIBRARY
        }
    }

    // 进程被系统回收后恢复：Activity 重建但 ViewModel 是新的，播放器里没有媒体，
    // 这时直接回首页，避免停在空白的播放页。
    LaunchedEffect(showPlayer, state.hasVideo) {
        if (showPlayer && !state.hasVideo) {
            showPlayer = false
        }
    }

    if (showPlayer) {
        PlayerScreen(
            viewModel = playerViewModel,
            onBack = { showPlayer = false },
            onBackToGallery = {
                showPlayer = false
                tab = HomeTab.GALLERY
            }
        )
    } else {
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    HomeTab.entries.forEach { item ->
                        Tab(
                            selected = tab == item,
                            onClick = { tab = item },
                            text = { Text(text = item.title) }
                        )
                    }
                }

                when (tab) {
                    HomeTab.LIBRARY -> LibraryScreen(
                        viewModel = libraryViewModel,
                        compact = compact,
                        onOpenVideo = { video ->
                            playerViewModel.openLibraryVideo(video)
                            showPlayer = true
                        },
                        onPickFromGallery = { tab = HomeTab.GALLERY },
                        onPickFromSaf = {
                            runCatching { safPicker.launch(arrayOf("video/*")) }
                        }
                    )

                    HomeTab.GALLERY -> GalleryScreen(
                        viewModel = galleryViewModel,
                        onPlay = { video, _ ->
                            // 相册已退化为导入来源：点击就入库，播放统一走媒体库
                            libraryViewModel.importUris(listOf(video.uri))
                            tab = HomeTab.LIBRARY
                            runCatching {
                                Toast.makeText(
                                    context,
                                    "已加入导入队列，完成后可在媒体库播放",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onRequestSafPick = {
                            runCatching { safPicker.launch(arrayOf("video/*")) }
                        }
                    )
                }
            }
        }
    }
}
