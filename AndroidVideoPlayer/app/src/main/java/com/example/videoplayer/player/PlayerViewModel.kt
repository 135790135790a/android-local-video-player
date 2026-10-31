package com.example.videoplayer.player

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.videoplayer.data.Playability
import com.example.videoplayer.data.PlayabilityChecker
import com.example.videoplayer.data.RecentVideo
import com.example.videoplayer.data.RecentVideoStore
import com.example.videoplayer.data.VideoMetaReader
import com.example.videoplayer.data.db.AppDatabase
import com.example.videoplayer.data.db.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

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
    val source: VideoSource = VideoSource.SAF,
    /** 当前挂载的外挂字幕文件名；null 表示没有字幕 */
    val subtitleName: String? = null
)

/**
 * 播放器的 UI 侧入口。
 *
 * v1.0.5 起播放器实例归 [PlaybackService] 所有，本类通过 [MediaController] 连接过去：
 * - 播放器生命周期不再绑在 Activity / ViewModel 上，旋转、切后台、锁屏都不中断
 * - 通知栏、锁屏、耳机线控和这里操作的是同一个播放器，状态天然一致
 *
 * 连接是异步的，所以 [player] 在连接完成前为 null；UI 层对无播放器的画面要能容忍。
 *
 * 生命周期约定：
 * - Activity.onStop  -> [onHostStop]（只落一次进度，不停播）
 * - Activity.onStart -> [onHostStart]
 * - Activity.onDestroy（非配置变更）/ ViewModel 清理 -> [releasePlayer]（断开控制器，不释放播放器）
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application), Player.Listener {

    private val app: Application = application
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val store = RecentVideoStore(application)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _recentVideos = MutableStateFlow(store.load())
    val recentVideos: StateFlow<List<RecentVideo>> = _recentVideos.asStateFlow()

    /** 连接完成后可用；连接前为 null，`PlayerView` 拿 null 是合法的（显示空白） */
    @Volatile
    private var controller: MediaController? = null

    /** 供播放页 / 手势层读取；连接前为 null */
    val player: Player? get() = controller

    private var progressJob: Job? = null
    private var released = false
    private var openRequestId = 0
    private val volumeController = VolumeController(application)
    private val audioFocus = AudioFocusController(
        context = application,
        onPause = { if (!released) player?.pause() },
        onResume = { if (!released) player?.play() },
        isPlaying = { !released && player?.isPlaying == true }
    )

    /** 进度续播：只有播放媒体库条目时才记 id，SAF/相册来源没有数据库记录 */
    private val dao = AppDatabase.get(application).videoDao()
    private val positionPersister = PositionPersister(dao)
    /** 连播队列：从媒体库点开时传入当前筛选/排序后的列表 */
    private var playlist: List<VideoEntity> = emptyList()
    private var playlistIndex = -1
    private var pendingSeekMs = 0L
    private var lastPersistAt = 0L

    init {
        connectController()
        // 音量由 VolumeController 监听系统变化后推过来，这里只消费结果更新 UI 状态
        viewModelScope.launch {
            combine(volumeController.volumePercent, volumeController.isMuted) { percent, muted ->
                val level = when {
                    muted -> VolumeLevel.MUTE
                    percent < 0.66f -> VolumeLevel.LOW
                    else -> VolumeLevel.HIGH
                }
                (percent * 100).roundToInt() to level
            }.collect { (percent, level) ->
                _uiState.update { it.copy(volumePercent = percent, volumeLevel = level) }
            }
        }
    }

    /** 连上后台服务的 MediaSession；连不上就保持无播放器状态，不阻塞 UI */
    private fun connectController() {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        runCatching {
            val future = MediaController.Builder(app, token).buildAsync()
            future.addListener(
                Runnable {
                    val c = runCatching { future.get() }.getOrNull()
                    if (c == null || released) {
                        runCatching { c?.release() }
                        return@Runnable
                    }
                    controller = c
                    c.addListener(this)
                    restoreSavedSpeed()
                },
                ContextCompat.getMainExecutor(app)
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
        // 非媒体库来源不记续播位置；LIBRARY 走「重试」时保留，续播信息不能丢
        if (source != VideoSource.LIBRARY) {
            positionPersister.bind(null)
            pendingSeekMs = 0L
        }
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
                source = source,
                subtitleName = null
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
    fun openLibraryVideo(video: VideoEntity, queue: List<VideoEntity> = emptyList()) {
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
        positionPersister.bind(video.id)
        playlist = queue.filter { it.isPlayable }
        playlistIndex = playlist.indexOfFirst { it.id == video.id }
        pendingSeekMs = video.lastPositionMs
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { dao.markPlayed(video.id, System.currentTimeMillis()) }
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
                bufferedMs = 0L,
                subtitleName = null
            )
        }

        val p = player
        if (p == null) {
            // 控制器还没连上（极少数情况）：等连接完成后再走一遍
            viewModelScope.launch {
                repeat(50) {
                    if (player != null || released) return@repeat
                    delay(100L)
                }
                if (!released && player != null) prepareVideo(uri, meta, silentAudio)
            }
            return
        }

        // 视频轨能解码、音频轨不能：把播放器音量乘数拉到 0 做「只放画面」。
        // player.volume 是独立于系统音量的乘数，用户再按音量键也不会出声。
        p.volume = if (silentAudio) 0f else 1f
        if (silentAudio) {
            runCatching {
                Toast.makeText(app, "音频格式不支持，将静音播放", Toast.LENGTH_LONG).show()
            }
        }

        p.setMediaItem(item)
        p.prepare()
        p.playWhenReady = true
        audioFocus.request()
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
    // 外挂字幕
    // ------------------------------------------------------------------

    /**
     * 挂载外挂字幕。
     *
     * 字幕是 [MediaItem] 的一部分，换字幕必须换掉当前 MediaItem；为了不打断观看，
     * 这里从当前位置重载（`setMediaItem(item, positionMs)`），而不是让播放位置跳回 0。
     *
     * 支持 .srt / .vtt / .ass / .ssa，全部交给 ExoPlayer 自带解析器
     * （SubRip / WebVTT / SSA），不自己解析。ASS 的特效标签会被忽略，只保留文本与基本样式。
     */
    fun attachSubtitle(uri: Uri, displayName: String) {
        if (released) return
        val mime = subtitleMimeOf(displayName)
        if (mime == null) {
            Toast.makeText(app, "只支持 .srt / .vtt / .ass / .ssa 字幕文件", Toast.LENGTH_LONG).show()
            return
        }
        val p = player ?: return
        val current = p.currentMediaItem ?: return
        val position = p.currentPosition.coerceAtLeast(0L)
        val wasPlaying = p.isPlaying

        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLanguage("zh")
            // 挂上就自动选中，用户不用再去轨道菜单里挑
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val rebuilt = current.buildUpon()
            .setSubtitleConfigurations(listOf(subtitle))
            .build()

        // 之前「关闭字幕」可能把文本轨禁掉了，重新挂载时要放开
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        p.setMediaItem(rebuilt, position)
        p.prepare()
        if (wasPlaying) p.play()
        _uiState.update { it.copy(subtitleName = displayName) }
    }

    /**
     * 关闭字幕：只禁用文本轨，不重载媒体。
     *
     * 这样播放不中断、缓冲不重来；再次挂载时把文本轨放开即可。
     */
    fun detachSubtitle() {
        if (released) return
        val p = player ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        _uiState.update { it.copy(subtitleName = null) }
    }

    /** 按扩展名判定字幕格式；不认识的返回 null，由调用方提示用户。 */
    private fun subtitleMimeOf(displayName: String): String? =
        when (displayName.substringAfterLast('.', "").lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> null
        }

    // ------------------------------------------------------------------
    // 播放控制
    // ------------------------------------------------------------------

    fun togglePlayPause() {
        if (released) return
        val p = player ?: return
        when {
            p.playbackState == Player.STATE_ENDED -> {
                p.seekTo(0L)
                p.play()
            }

            p.isPlaying -> p.pause()
            else -> {
                audioFocus.request()
                p.play()
            }
        }
    }

    fun play() {
        if (released) return
        audioFocus.request()
        player?.play()
    }

    fun pause() {
        if (released) return
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        val p = player ?: return
        // 准备完成前 duration 可能是 C.TIME_UNSET，不能直接当上界（会把位置压成 0）
        val duration = p.duration
        val upperBound = if (duration == C.TIME_UNSET || duration < 0L) Long.MAX_VALUE else duration
        p.seekTo(positionMs.coerceIn(0L, upperBound))
        syncProgress()
    }

    fun seekBy(deltaMs: Long) {
        seekTo((player?.currentPosition ?: 0L) + deltaMs)
    }

    fun setSpeed(speed: Float) {
        if (released) return
        player?.setPlaybackSpeed(speed)
        _uiState.update { it.copy(speed = speed) }
        prefs.edit().putFloat(KEY_SPEED, speed).apply()
    }

    fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}.0x" else "${speed}x"

    // ------------------------------------------------------------------
    // 音量：走 AudioManager.STREAM_MUSIC，不用 player.volume
    // ------------------------------------------------------------------

    /** 三档循环：静音 → 低 → 高 → 静音 */
    fun cycleVolume() {
        when (_uiState.value.volumeLevel) {
            VolumeLevel.MUTE -> volumeController.setPercent(0.34f)
            VolumeLevel.LOW -> volumeController.setPercent(1f)
            VolumeLevel.HIGH -> volumeController.toggleMute()
        }
    }

    /** 上下滑动手势用，delta 为百分点步进。 */
    fun adjustVolumeBy(delta: Int) {
        val current = _uiState.value.volumePercent.coerceIn(0, 100)
        volumeController.setPercent((current + delta).coerceIn(0, 100) / 100f)
    }

    fun setVolumePercent(percent: Int) {
        volumeController.setPercent(percent.coerceIn(0, 100) / 100f)
    }

    /** 只恢复倍速；音量由 VolumeController 自己恢复 */
    private fun restoreSavedSpeed() {
        val savedSpeed = prefs.getFloat(KEY_SPEED, 1f)
        if (savedSpeed in SPEED_OPTIONS) {
            player?.setPlaybackSpeed(savedSpeed)
            _uiState.update { it.copy(speed = savedSpeed) }
        }
    }

    // 音频焦点的申请 / 放弃统一委托给 AudioFocusController

    // ------------------------------------------------------------------
    // 宿主 Activity 生命周期
    // ------------------------------------------------------------------

    fun onHostStop() {
        // 后台播放：切走时不暂停，由 PlaybackService 里的 MediaSession 继续托管。
        // 这里只落一次进度，避免进程被回收时丢位置。
        persistPosition()
    }

    fun onHostStart() {
        // 回到前台不用做什么：控制器连的就是同一个播放器，状态会自动同步过来
    }

    fun releasePlayer() {
        if (released) return
        released = true
        persistPosition()
        progressJob?.cancel()
        volumeController.release()
        positionPersister.release()
        audioFocus.release()
        // 只断开控制器，播放器本身归服务管；服务会在划掉任务时停掉
        runCatching {
            controller?.removeListener(this)
            controller?.release()
        }
        controller = null
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
                isPlaying = player?.isPlaying == true
            )
        }
        if (playbackState == Player.STATE_READY) {
            // 续播位置只能在真正准备好之后 seek，prepare 之前的 seek 会被忽略
            if (pendingSeekMs > 0L) {
                val target = pendingSeekMs
                pendingSeekMs = 0L
                val duration = player?.duration ?: C.TIME_UNSET
                if (duration != C.TIME_UNSET && duration > 0L && target < duration - 3_000L) {
                    player?.seekTo(target)
                }
            }
            syncProgress()
        }
        if (playbackState == Player.STATE_ENDED) {
            // 看完了就归零，下次从头播
            persistPosition(forceZero = true)
            advanceToNext()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
        if (isPlaying) {
            audioFocus.request()
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
                // 每 5 秒落一次库，避免进程被系统杀掉时丢进度
                if (System.currentTimeMillis() - lastPersistAt >= 5_000L) {
                    lastPersistAt = System.currentTimeMillis()
                    persistPosition()
                }
                delay(if (player?.isPlaying == true) 250L else 600L)
            }
        }
    }

    private fun syncProgress() {
        if (released) return
        val p = player ?: return
        val duration = p.duration
        val safeDuration = if (duration == C.TIME_UNSET || duration < 0) 0L else duration
        val position = p.currentPosition.coerceAtLeast(0L)
        val buffered = p.bufferedPosition.coerceAtLeast(0L)
        _uiState.update { current ->
            current.copy(
                durationMs = if (safeDuration > 0) safeDuration else current.durationMs,
                positionMs = position,
                bufferedMs = if (safeDuration > 0) buffered.coerceAtMost(safeDuration) else buffered
            )
        }
    }

    /** 连播：一条播完自动播队列里的下一条；到队尾或文件已丢失就停在当前页 */
    private fun advanceToNext() {
        if (released || playlistIndex < 0) return
        val next = playlist.getOrNull(playlistIndex + 1) ?: return
        if (!java.io.File(next.localPath).exists()) return
        openLibraryVideo(next, playlist)
    }

    /**
     * 把当前播放位置写回数据库。
     *
     * 只在播放媒体库条目时生效；离结尾不足 3 秒视为「看完」，归零。
     * 走 [PositionPersister] 自己的 scope，即使 viewModelScope 已取消也能落库。
     */
    private fun persistPosition(forceZero: Boolean = false) {
        val p = player
        val position = runCatching { p?.currentPosition ?: 0L }.getOrDefault(0L)
        val duration = runCatching { p?.duration ?: 0L }.getOrDefault(0L)
        positionPersister.save(position, duration, forceZero)
    }

    companion object {
        private const val PREFS_NAME = "player_prefs"
        private const val KEY_SPEED = "playback_speed"

        val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    }
}
