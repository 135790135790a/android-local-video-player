package com.example.videoplayer

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.videoplayer.data.LegacyMigrator
import com.example.videoplayer.player.PlayerViewModel
import com.example.videoplayer.ui.AppRoot
import com.example.videoplayer.ui.LocalPipMode
import com.example.videoplayer.ui.theme.VideoPlayerTheme
import com.example.videoplayer.util.CrashGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    /** 画中画状态：播放页据此隐藏控制栏（PiP 窗口太小，控件会盖住画面） */
    private val pipMode = mutableStateOf(false)

    /**
     * Android 13 起通知是运行时权限。
     * 不授予也能后台播放，只是媒体通知不显示，所以这里拒绝后不做任何打扰。
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 崩溃兜底：未捕获异常写日志到私有目录，下次启动提示；不吞异常
        CrashGuard.install(applicationContext)
        if (savedInstanceState == null && CrashGuard.consumePendingCrash(this)) {
            Toast.makeText(this, "上次运行异常退出，已保存崩溃日志", Toast.LENGTH_LONG).show()
        }
        // v1.0.1 的「最近播放」是 content:// 授权 + SharedPreferences，v1.0.2 起改成入库；
        // 老用户升级时把还能读到的旧记录搬进媒体库（只跑一次，内部有迁移标记）
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { LegacyMigrator.migrateIfNeeded(applicationContext) }
        }

        requestNotificationPermission()

        enableEdgeToEdge()
        setContent {
            VideoPlayerTheme {
                CompositionLocalProvider(LocalPipMode provides pipMode.value) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppRoot(playerViewModel = viewModel)
                    }
                }
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        // 从后台回到前台：如果之前是被动暂停的，继续播放
        viewModel.onHostStart()
    }

    override fun onStop() {
        super.onStop()
        // 后台播放：onHostStop 现在只记录进度，不再暂停播放
        viewModel.onHostStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 旋转等配置变更不释放播放器（ViewModel 继续持有），只有真正销毁时才 release
        if (!isChangingConfigurations) {
            viewModel.releasePlayer()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
    }
}
