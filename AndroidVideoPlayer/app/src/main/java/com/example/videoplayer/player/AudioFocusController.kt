package com.example.videoplayer.player

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

/**
 * 音频焦点：来电 / 其他 App 播放时让出，独占结束后按需续播。
 *
 * 单独成类，PlayerViewModel 只管「播什么」，不用同时照看焦点状态机。
 */
class AudioFocusController(
    context: Context,
    private val onPause: () -> Unit,
    private val onResume: () -> Unit,
    private val isPlaying: () -> Boolean
) {
    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnGain = false
    @Volatile
    private var released = false

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (released) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnGain = false
                if (isPlaying()) onPause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnGain = isPlaying()
                if (isPlaying()) onPause()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnGain && !released) {
                    resumeOnGain = false
                    onResume()
                }
            }
        }
    }

    /** 返回 true 表示焦点已拿到。 */
    fun request(): Boolean {
        if (released) return false
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    // 这里要平台的 android.media.AudioAttributes；
                    // media3 有同名类，所以用全限定名区分
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(true)
                .build()
                .also { focusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    fun abandon() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(listener)
            }
        }
    }

    /** 播放器销毁时调用，之后任何焦点回调都会被忽略。 */
    fun release() {
        released = true
        abandon()
    }
}
