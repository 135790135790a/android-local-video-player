package com.example.videoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * 启动画面：全屏静音播放开场动画。
 *
 * 退出条件（任一满足即进主界面）：
 * - 视频正常播完
 * - 用户点击屏幕任意处，或点右下角「跳过」
 * - 播放器报错（解码失败、资源缺失）
 * - 20 秒还没结束（兜底）
 *
 * 播放器用 media3 的 ExoPlayer 而不是系统 VideoView：
 * VideoView 底层是 SurfaceView，在部分 ROM 上会出现「解码在走、画面全黑」的情况，
 * 而这里配合 PlayerView 的 texture_view 模式走普通 View 绘制，兼容性明显更好。
 */
class SplashActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 防止重复跳转：播完回调和点击可能同时触发。 */
    private var hasLeft = false

    private val timeout = Runnable { goMain() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        playerView = findViewById(R.id.splash_player)
        findViewById<View>(R.id.splash_root).setOnClickListener { goMain() }
        findViewById<View>(R.id.splash_skip).setOnClickListener { goMain() }

        handler.postDelayed(timeout, TIMEOUT_MS)

        val video = prepareVideoFile()
        if (video == null) {
            goMain()
            return
        }
        startPlayback(video)
    }

    private fun startPlayback(video: File) {
        val exo = ExoPlayer.Builder(this).build()
        player = exo
        playerView?.player = exo

        // 启动动画静音：不打断用户正在听的音乐，也就不去抢音频焦点
        exo.volume = 0f
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(video)))
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) goMain()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 兜底：机型不支持 / 文件异常时直接进主界面，并把原因打出来便于排查
                Log.w(TAG, "splash playback failed: ${error.errorCodeName}", error)
                Toast.makeText(
                    this@SplashActivity,
                    "开场动画播放失败（${error.errorCodeName}），已跳过",
                    Toast.LENGTH_SHORT
                ).show()
                goMain()
            }
        })
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * 把 raw 里的开场视频复制到 cache，再用文件路径交给播放器。
     *
     * 不用 `android.resource://` 直接播：那条路要经过 ContentResolver 打开 raw，
     * 部分 ROM 会失败。落地成普通文件后走的是最通用的播放路径。
     *
     * 文件名带 versionCode，App 升级后旧缓存自然失效，不需要额外清理逻辑。
     */
    private fun prepareVideoFile(): File? = runCatching {
        val versionCode = packageManager.getPackageInfo(packageName, 0).versionCode
        val file = File(cacheDir, "splash_intro_$versionCode.mp4")
        if (file.exists() && file.length() > 0L) {
            return@runCatching file
        }
        resources.openRawResource(R.raw.splash_intro).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        if (file.length() <= 0L) null else file
    }.onFailure {
        Log.w(TAG, "copy splash video failed", it)
    }.getOrNull()

    private fun goMain() {
        if (hasLeft) return
        hasLeft = true
        handler.removeCallbacks(timeout)
        startActivity(Intent(this, MainActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        playerView?.player = null
        player?.release()
        player = null
    }

    private companion object {
        const val TAG = "Splash"

        /** 视频 15 秒，留出解码与首帧的余量。 */
        const val TIMEOUT_MS = 20_000L
    }
}
