package com.example.videoplayer.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride

/**
 * 音轨选择项。
 *
 * [index] 是「第几条音频轨」在 TrackGroup 列表里的位置，不是全局轨道号。
 */
data class AudioTrackOption(
    val index: Int,
    val label: String,
    val isSelected: Boolean
)

/**
 * 音轨与外挂字幕的集中管理。
 *
 * 从 PlayerViewModel 里拆出来：这两件事都只跟「当前播放器的轨道状态」有关，
 * 不涉及数据库、进度落库、手势，混在一起会把 ViewModel 越堆越厚。
 *
 * 播放器实例通过 [playerProvider] 每次现取 —— UI 侧是 MediaController，
 * 连接/断开由 ViewModel 管，这里不持有引用，避免拿到已释放的对象。
 */
class TrackController(
    private val playerProvider: () -> Player?
) {

    /** 从播放器当前轨道重建音轨列表；播放器还没准备好时返回空列表 */
    fun audioTracks(): List<AudioTrackOption> {
        val p = playerProvider() ?: return emptyList()
        return p.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .mapIndexed { index, group ->
                AudioTrackOption(
                    index = index,
                    label = audioLabel(group.mediaTrackGroup.getFormat(0)),
                    isSelected = group.isSelected
                )
            }
    }

    /**
     * 切换到第 [index] 条音轨。
     *
     * 用 [TrackSelectionOverride] 只覆盖音频类型，视频轨和字幕轨保持原样；
     * 参数会被 MediaController 跨进程同步给服务里的播放器。
     */
    fun selectAudioTrack(index: Int): Boolean {
        val p = playerProvider() ?: return false
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val group = groups.getOrNull(index)?.mediaTrackGroup ?: return false
        return runCatching {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group, listOf(0)))
                .build()
        }.isSuccess
    }

    /**
     * 挂载外挂字幕，返回 false 表示扩展名不支持或当前没有可挂载的媒体。
     *
     * 字幕是 [MediaItem] 的一部分，换字幕必须换掉当前 MediaItem；为了不打断观看，
     * 这里从当前位置重载（`setMediaItem(item, positionMs)`），而不是让播放位置跳回 0。
     * 支持 .srt / .vtt / .ass / .ssa，全部交给 ExoPlayer 自带解析器
     * （SubRip / WebVTT / SSA），不自己解析。ASS 的特效标签会被忽略，只保留文本与基本样式。
     */
    fun attachSubtitle(uri: Uri, displayName: String): Boolean {
        val mime = subtitleMimeOf(displayName) ?: return false
        val p = playerProvider() ?: return false
        val current = p.currentMediaItem ?: return false
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
        return true
    }

    /**
     * 关闭字幕：只禁用文本轨，不重载媒体。
     *
     * 这样播放不中断、缓冲不重来；再次挂载时把文本轨放开即可。
     */
    fun detachSubtitle() {
        val p = playerProvider() ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    /** 音轨文案：优先用容器里的轨道名，其次语言，最后补声道数 */
    private fun audioLabel(format: Format): String {
        val name = format.label?.takeIf { it.isNotBlank() }
        val lang = format.language?.takeIf { it.isNotBlank() }?.let(::languageName)
        val head = listOfNotNull(name, lang).distinct().joinToString(" · ").ifBlank { "默认音轨" }
        return when (format.channelCount) {
            0 -> head
            1 -> "$head · 单声道"
            2 -> "$head · 立体声"
            else -> "$head · ${format.channelCount} 声道"
        }
    }

    private fun languageName(code: String): String = when (code.substringBefore('-').lowercase()) {
        "zh" -> "中文"
        "en" -> "英语"
        "ja" -> "日语"
        "ko" -> "韩语"
        "fr" -> "法语"
        "de" -> "德语"
        "es" -> "西班牙语"
        "ru" -> "俄语"
        else -> code
    }

    private fun subtitleMimeOf(displayName: String): String? =
        when (displayName.substringAfterLast('.', "").lowercase()) {
            "srt" -> MimeTypes.APPLICATION_SUBRIP
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            else -> null
        }
}
