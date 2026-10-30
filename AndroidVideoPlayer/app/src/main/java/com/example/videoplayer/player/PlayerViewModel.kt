package com.example.videoplayer.player

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.videoplayer.data.Playability
import com.example.videoplayer.data.PlayabilityChecker
import com.example.videoplayer.data.RecentVideo
import com.example.videoplayer.data.RecentVideoStore
import com.example.videoplayer.data.VideoMetaReader
import com.example.videoplayer.data.db.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音量三档：静音 / 低 / 高 */
enum class VolumeLevel { MUTE, LOW, HIGH }

/** 本次播放的来源页面，「返回」按钮据此决定回到哪里。 */
enum class VideoSource { SAF, GALLERY, RECENT, LIBRARY }

/** 播放前的预检拦截结论，等用户决定是否仍然强播。 */
data class PreflightBlock(val reason: String, val detail: String)

data class PlayerUiState(
    val videoName: String = "",
    val videoUri: String = "",
    val hasVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isReady: Boolean = false,
    val ended: Boolean = false,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
    val volumeLevel: VolumeLevel = VolumeLevel.HIGH,
    val volumePercent: Int = 100,
    val errorMessage: String? = null,
    val errorDetail: String? = null,
    /** 正在做可播放性预检；UI 侧延迟 200ms 才展示 loading，避免一闪而过。 */
    val isChecking: Boolean = false,
    /** 预检判定不可播，等用户确认是否仍然强播。 */
    val preflightBlock: PreflightBlock? = null,
    /** 本次播放的来源页面。 */
    val source: VideoSource = VideoSource.SAF
)

/**
 * 播放器的唯一持有者。放在 ViewModel 里，Activity 因旋转/主题变化重建时播放不中断。
 *
 * 生命周期约定：
 * - Activity.onStop  -> [onHostStop]（暂停并让出音频焦点）
 * - Activity.onStart -> [onHostStart]（之前在播放则恢复）
 * - Activity.onDestroy（非配置变更）/ ViewModel 清理 -> [releasePlayer]（真正 release）
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application), Player.Listener {

    private val app: Application = application
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val store = RecentVideoStore(application)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _recentVideos = MutableStateFlow(store.load())
    val recentVideos: StateFlow<List<RecentVideo>> = _recentVideos.asStateFlow()

    /**
     * handleAudioFocus 传 false：音频焦点由本类统一申请 / 放弃，
     * 避免 ExoPlayer 内部的焦点策略和我们的来电暂停逻辑互相抢占。
     */
    val player: ExoPlayer = ExoPlayer.Builder(application)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            false
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // 播放器可能已经 release（Activity 真正销毁），此时不能再操作它
        if (released) return@OnAudioFocusChangeListener
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                resumeOnFocusGain = false
                if (player.isPlaying) {
                    player.pause()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                resumeOnFocusGain = player.isPlaying
                if (player.isPlaying) {
                    player.pause()
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                if (resumeOnFocusGain && !released) {
                    resumeOnFocusGain = false
                    player.play()
                }
            }
        }
    }

    /** 用户在系统音量键 / 设置里改了音量时同步 UI 的三档图标。 */
    private val volumeObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshVolumeState()
            prefs.edit().putInt(KEY_VOLUME_PERCENT, _uiState.value.volumePercent).apply()
        }
    }

    private var progressJob: Job? = null
    private var released = false
    private var openRequestId = 0
    private var resumeOnFocusGain = false
    private var resumeAfterStop = false
    private var audioFocusRequest: AudioFocusRequest? = null

    init {
        player.addListener(this)
        restoreSavedVolume()
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                volumeObserver
            )
        }
    }

    // ------------------------------------------------------------------
    // 打开视频
    // ------------------------------------------------------------------

    /**
     * 打开一个视频。
     *
     * 默认会先做一次可播放性预检（MediaExtractor 读轨道 + MediaCodecList 找解码器），
     * 不可播时不进播放器，而是抛给 UI 让用户决定「仍要尝试播放」。
     *
     * @param source 来源页面，「返回」按钮据此决定回到哪里
     * @param skipPreflight true 时跳过预检直接播（用户选「仍要尝试播放」或「重试」）
     */
    fun openVideo(
        uri: Uri,
        source: VideoSource = VideoSource.SAF,
        skipPreflight: Boolean = false
    ) {
        // 快速连点两个视频时只让最后一次请求生效，避免「点了 B 却在播 A」
        val requestId = ++openRequestId
        // 先把占位状态同步推给 UI：播放页立即进入 loading，而不是闪一下空白
        _uiState.update {
            it.copy(
                videoName = "",
                videoUri = uri.toString(),
                hasVideo = true,
                isBuffering = false,
                isChecking = !skipPreflight,
                isPlaying = false,
                isReady = false,
                ended = false,
                errorMessage = null,
                errorDetail = null,
                preflightBlock = null,
                durationMs = 0L,
                positionMs = 0L,
                bufferedMs = 0L,
                source = source
            )
        }
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { VideoMetaReader.read(app, uri) }
            // 期间又打开了别的视频，或者播放器已释放：本次结果直接丢弃
            if (released || requestId != openRequestId) return@launch

            if (skipPreflight) {
                prepareVideo(uri, meta, silentAudio = false)
                return@launch
            }

            // 第二级校验：MediaExtractor + MediaCodecList，全程在 IO 线程
            val verdict = PlayabilityChecker.deepCheck(
                context = app,
                uri = uri,
                cacheKey = "${uri}|${meta.sizeBytes}"
            )
            if (released || requestId != openRequestId) return@launch

            when (verdict) {
                is Playability.Unsupported -> _uiState.update {
                    it.copy(
                        isChecking = false,
                        isBuffering = false,
                        preflightBlock = PreflightBlock(verdict.reason, verdict.detail)
                    )
                }

                is Playability.SilentOnly -> prepareVideo(uri, meta, silentAudio = true)
                is Playability.Supported -> prepareVideo(uri, meta, silentAudio = false)
            }
        }
    }

    /**
     * 播放媒体库里已经入库的本地文件。
     *
     * 本地文件在导入时已经做过解码能力探测并写进数据库，这里不再重复预检；
     * 播放源永远是私有目录下的 File，不依赖 content:// 授权是否还有效。
     */
    fun openLibraryVideo(video: VideoEntity) {
        if (released) return
        val file = java.io.File(video.localPath)
        if (!file.exists()) {
            _uiState.update {
                it.copy(
                    errorMessage = "本地文件已丢失",
                    errorDetail = video.localPath
                )
            }
            return
        }
        _uiState.update { it.copy(source = VideoSource.LIBRARY) }
        val meta = VideoMetaReader.Meta(
            displayName = video.fileName,
            sizeBytes = video.size,
            durationMs = video.durationMs
        )
        prepareVideo(Uri.fromFile(file), meta, silentAudio = false)
    }

    private fun prepareVideo(
        uri: Uri,
        meta: VideoMetaReader.Meta,
        silentAudio: Boolean
    ) {
        if (released) return
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(meta.displayName).build())
            .build()

        _uiState.update {
            it.copy(
                videoName = meta.displayName,
                videoUri = uri.toString(),
                hasVideo = true,
                isChecking = false,
                preflightBlock = null,
                isBuffering = true,
                isPlaying = false,
                isReady = false,
                ended = false,
                errorMessage = null,
                errorDetail = null,
                durationMs = meta.durationMs,
                positionMs = 0L,
                bufferedMs = 0L
            )
        }

        // 视频轨能解码、音频轨不能：把播放器音量乘数拉到 0 做「只放画面」。
        // player.volume 是独立于系统音量的乘数，用户再按音量键也不会出声。
        player.volume = if (silentAudio) 0f else 1f
        if (silentAudio) {
            runCatching {
                Toast.makeText(app, "音频格式不支持，将静音播放", Toast.LENGTH_LONG).show()
            }
        }

        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = true
        requestAudioFocus()
        startProgressTicker()

        // 媒体库的视频由 Room 记录播放历史，不再写旧的最近播放入口
        if (_uiState.value.source != VideoSource.LIBRARY) {
            val record = RecentVideo(
                uri = uri.toString(),
                displayName = meta.displayName,
                sizeBytes = meta.sizeBytes,
                durationMs = meta.durationMs,
                lastPlayedAt = System.currentTimeMillis()
            )
            _recentVideos.value = store.upsert(record)
        }
    }

    /** 播放失败后重试当前视频：用户已经知道它可能有问题，跳过预检直接重试。 */
    fun retry() {
        val uriString = _uiState.value.videoUri
        if (uriString.isBlank()) return
        openVideo(Uri.parse(uriString), _uiState.value.source, skipPreflight = true)
    }

    /** 预检拦截后用户选择「仍要尝试播放」。 */
    fun playAnyway() {
        val uriString = _uiState.value.videoUri
        if (uriString.isBlank()) return
        openVideo(Uri.parse(uriString), _uiState.value.source, skipPreflight = true)
    }

    /** 预检拦截后用户点了「取消」：丢掉这个待播放的视频。 */
    fun dismissPreflightBlock() {
        _uiState.update {
            it.copy(preflightBlock = null, isChecking = false, isBuffering = false)
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null, errorDetail = null) }
    }

    // ------------------------------------------------------------------
    // 播放控制
    // ------------------------------------------------------------------

    fun togglePlayPause() {
        if (released) return
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0L)
                player.play()
            }

            player.isPlaying -> player.pause()
            else -> {
                requestAudioFocus()
                player.play()
            }
        }
    }

    fun play() {
        if (released) return
        requestAudioFocus()
        player.play()
    }

    fun pause() {
        if (released) return
        player.pause()
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        // 准备完成前 duration 可能是 C.TIME_UNSET，不能直接当上界（会把位置压成 0）
        val duration = player.duration
        val upperBound = if (duration == C.TIME_UNSET || duration < 0L) Long.MAX_VALUE else duration
        player.seekTo(positionMs.coerceIn(0L, upperBound))
        syncProgress()
    }

    fun seekBy(deltaMs: Long) {
        seekTo(player.currentPosition + deltaMs)
    }

    fun setSpeed(speed: Float) {
        if (released) return
        player.setPlaybackSpeed(speed)
        _uiState.update { it.copy(speed = speed) }
        prefs.edit().putFloat(KEY_SPEED, speed).apply()
    }

    fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    // ------------------------------------------------------------------
    // 音量：走 AudioManager.STREAM_MUSIC，不用 player.volume
    // ------------------------------------------------------------------

    fun cycleVolume() {
        val max = maxVolume()
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = when {
            current <= 0 -> (max / 3).coerceAtLeast(1)
            current < max * 2 / 3 -> max
            else -> 0
        }
        applyVolume(target)
    }

    /** 上下滑动手势用，delta 为 0..max 的步进。 */
    fun adjustVolumeBy(delta: Int) {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        applyVolume(current + delta)
    }

    fun setVolumePercent(percent: Int) {
        applyVolume(maxVolume() * percent / 100)
    }

    private fun maxVolume(): Int =
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private fun applyVolume(level: Int) {
        val max = maxVolume()
        val target = level.coerceIn(0, max)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
        val percent = target * 100 / max
        _uiState.update {
            it.copy(volumePercent = percent, volumeLevel = levelFor(target, max))
        }
        prefs.edit().putInt(KEY_VOLUME_PERCENT, percent).apply()
    }

    private fun levelFor(value: Int, max: Int): VolumeLevel = when {
        value <= 0 -> VolumeLevel.MUTE
        value * 100 / max < 66 -> VolumeLevel.LOW
        else -> VolumeLevel.HIGH
    }

    private fun refreshVolumeState() {
        val max = maxVolume()
        val current = runCatching {
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }.getOrDefault(0)
        _uiState.update {
            it.copy(volumePercent = current * 100 / max, volumeLevel = levelFor(current, max))
        }
    }

    private fun restoreSavedVolume() {
        val savedPercent = prefs.getInt(KEY_VOLUME_PERCENT, -1)
        val savedSpeed = prefs.getFloat(KEY_SPEED, 1f)
        if (savedSpeed in SPEED_OPTIONS) {
            player.setPlaybackSpeed(savedSpeed)
            _uiState.update { it.copy(speed = savedSpeed) }
        }
        if (savedPercent >= 0) {
            applyVolume(maxVolume() * savedPercent / 100)
        } else {
            refreshVolumeState()
        }
    }

    // ------------------------------------------------------------------
    // 音频焦点
    // ------------------------------------------------------------------

    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = audioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    // 这里要的是平台的 android.media.AudioAttributes，
                    // 而 media3 也有同名的类，所以用全限定名区分
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .setWillPauseWhenDucked(true)
                .build()
                .also { audioFocusRequest = it }
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        }
    }

    // ------------------------------------------------------------------
    // 宿主 Activity 生命周期
    // ------------------------------------------------------------------

    fun onHostStop() {
        resumeAfterStop = _uiState.value.isPlaying
        if (_uiState.value.isPlaying) {
            player.pause()
        }
        abandonAudioFocus()
    }

    fun onHostStart() {
        if (resumeAfterStop && !released) {
            resumeAfterStop = false
            requestAudioFocus()
            player.play()
        }
    }

    fun releasePlayer() {
        if (released) return
        released = true
        progressJob?.cancel()
        runCatching { app.contentResolver.unregisterContentObserver(volumeObserver) }
        abandonAudioFocus()
        runCatching {
            player.removeListener(this)
            player.release()
        }
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }

    // ------------------------------------------------------------------
    // 最近播放
    // ------------------------------------------------------------------

    fun removeRecent(uri: String) {
        _recentVideos.value = store.remove(uri)
    }

    fun clearRecent() {
        store.clear()
        _recentVideos.value = emptyList()
    }

    // ------------------------------------------------------------------
    // Player.Listener
    // ------------------------------------------------------------------

    override fun onPlaybackStateChanged(playbackState: Int) {
        _uiState.update {
            it.copy(
                isBuffering = playbackState == Player.STATE_BUFFERING,
                isReady = playbackState == Player.STATE_READY,
                ended = playbackState == Player.STATE_ENDED,
                isPlaying = player.isPlaying
            )
        }
        if (playbackState == Player.STATE_READY) {
            syncProgress()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
        if (isPlaying) {
            requestAudioFocus()
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        _uiState.update {
            it.copy(
                errorMessage = PlaybackErrorMapper.toUserMessage(error),
                errorDetail = PlaybackErrorMapper.technicalDetail(error),
                isChecking = false,
                isBuffering = false,
                isPlaying = false
            )
        }
    }

    // ------------------------------------------------------------------
    // 进度轮询
    // ------------------------------------------------------------------

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                syncProgress()
                delay(if (player.isPlaying) 250L else 600L)
            }
        }
    }

    private fun syncProgress() {
        if (released) return
        val duration = player.duration
        val safeDuration = if (duration == C.TIME_UNSET || duration < 0) 0L else duration
        val position = player.currentPosition.coerceAtLeast(0L)
        val buffered = player.bufferedPosition.coerceAtLeast(0L)
        _uiState.update { current ->
            current.copy(
                durationMs = if (safeDuration > 0) safeDuration else current.durationMs,
                positionMs = position,
                bufferedMs = if (safeDuration > 0) buffered.coerceAtMost(safeDuration) else buffered
            )
        }
    }

    companion object {
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_VOLUME_PERCENT = "volume_percent"
        private const val KEY_SPEED = "playback_speed"

        val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
