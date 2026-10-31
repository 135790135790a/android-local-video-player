package com.example.videoplayer.player

import com.example.videoplayer.data.db.VideoDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 播放进度落库。
 *
 * 用独立 scope：ViewModel 被清理时 viewModelScope 已经取消，
 * 最后一次进度还得写进去，否则「退出前看了 20 分钟」会白看。
 */
class PositionPersister(private val dao: VideoDao) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var videoId: Long? = null

    /** 绑定媒体库条目；非媒体库来源传 null，后续 save 全部忽略。 */
    fun bind(id: Long?) {
        videoId = id
    }

    /**
     * 记下当前位置。离结尾不足 3 秒视为「看完」，存 0（下次从头播）。
     */
    fun save(position: Long, duration: Long, forceZero: Boolean = false) {
        val id = videoId ?: return
        val safe = if (duration > 0L && (forceZero || position >= duration - 3_000L)) {
            0L
        } else {
            position.coerceAtLeast(0L)
        }
        scope.launch { runCatching { dao.updatePosition(id, safe) } }
    }

    fun release() {
        scope.cancel()
    }
}
