package com.example.videoplayer.player

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * 后台播放与媒体控制中心。
 *
 * v1.0.5 起播放器归本服务所有：MediaSession 包住它，通知栏、锁屏控件、
 * 蓝牙耳机按键都作用在同一个实例上；UI 通过 MediaController 连进来操作。
 *
 * 这样播放器生命周期不再绑在 Activity 上 —— 划到后台、锁屏、旋转都不会中断，
 * 也不会再出现「UI 和通知栏状态打架」的问题。
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                // 音频焦点由 UI 侧的 AudioFocusController 统一申请，播放器内部不再插手
                false
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        exoPlayer = player

        val builder = MediaSession.Builder(this, player)
        buildSessionActivity()?.let { builder.setSessionActivity(it) }
        mediaSession = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉最近任务：停止播放并退出，不做「划掉还在后台放声音」的流氓行为。
        runCatching { exoPlayer?.pause() }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        runCatching { exoPlayer?.release() }
        exoPlayer = null
        super.onDestroy()
    }

    /** 点通知回到 App：没有可复用的任务栈时新建一个 */
    private fun buildSessionActivity(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
