package com.example.videoplayer.player

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音量控制器：直接操作 AudioManager 的 STREAM_MUSIC（不是 player.volume），
 * 这样实体音量键、系统静音、蓝牙耳机音量走的是同一个通道。
 *
 * 对外只暴露两件事：0..1 的音量百分比 + 是否静音，UI 据此换三档图标。
 */
class VolumeController(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val audioManager: AudioManager? =
        app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val maxVolume: Int =
        runCatching { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: 0

    private val _volumePercent = MutableStateFlow(0f)

    /** 0f..1f 的媒体音量百分比 */
    val volumePercent: StateFlow<Float> = _volumePercent.asStateFlow()

    private val _isMuted = MutableStateFlow(false)

    /** 系统媒体音量是否为 0 */
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    /** 用户按实体音量键时，系统会改 Settings，这里跟着同步 UI */
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            readSystemVolume()
        }
    }

    init {
        restoreSavedVolume()
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                observer,
            )
        }
        readSystemVolume()
    }

    /** 调高一格 */
    fun stepUp() = adjust(AudioManager.ADJUST_RAISE)

    /** 调低一格 */
    fun stepDown() = adjust(AudioManager.ADJUST_LOWER)

    /** 静音 / 取消静音切换 */
    fun toggleMute() {
        val am = audioManager ?: return
        val direction = if (_isMuted.value) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
        readSystemVolume()
    }

    /** 手势或滑块直接设置百分比 */
    fun setPercent(percent: Float) {
        if (maxVolume <= 0) return
        val target = (percent.coerceIn(0f, 1f) * maxVolume).toInt().coerceIn(0, maxVolume)
        runCatching {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        readSystemVolume()
    }

    private fun adjust(direction: Int) {
        runCatching {
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI,
            )
        }
        readSystemVolume()
    }

    /** 只在保存值与当前不同时才写回，避免启动时覆盖用户刚按出来的音量 */
    private fun restoreSavedVolume() {
        if (maxVolume <= 0) return
        val stored = prefs.getInt(KEY_VOLUME, -1)
        if (stored !in 0..maxVolume) return
        val current = currentStreamVolume()
        if (stored == current) return
        runCatching {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, stored, 0)
        }
    }

    private fun currentStreamVolume(): Int =
        runCatching { audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: 0

    private fun readSystemVolume() {
        val current = currentStreamVolume()
        _isMuted.value = maxVolume <= 0 || current == 0
        _volumePercent.value =
            if (maxVolume <= 0) 0f else (current.toFloat() / maxVolume).coerceIn(0f, 1f)
        prefs.edit { putInt(KEY_VOLUME, current) }
    }

    /** 播放器释放时注销监听，避免 ContentResolver 持有 Activity-less 的回调 */
    fun release() {
        runCatching { app.contentResolver.unregisterContentObserver(observer) }
    }

    private companion object {
        const val PREFS_NAME = "volume"
        const val KEY_VOLUME = "music_volume"
    }
}
