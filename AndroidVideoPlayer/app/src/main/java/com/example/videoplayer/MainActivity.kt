package com.example.videoplayer

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.videoplayer.data.LegacyMigrator
import com.example.videoplayer.player.PlayerViewModel
import com.example.videoplayer.ui.AppRoot
import com.example.videoplayer.ui.theme.VideoPlayerTheme
import com.example.videoplayer.util.CrashGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

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

        enableEdgeToEdge()
        setContent {
            VideoPlayerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(playerViewModel = viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // 从后台回到前台：如果之前是被动暂停的，继续播放
        viewModel.onHostStart()
    }

    override fun onStop() {
        super.onStop()
        // 退到后台先暂停，避免用户看不到画面却还在出声
        viewModel.onHostStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 旋转等配置变更不释放播放器（ViewModel 继续持有），只有真正销毁时才 release
        if (!isChangingConfigurations) {
            viewModel.releasePlayer()
        }
    }
}
