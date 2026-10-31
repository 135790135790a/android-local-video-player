package com.example.videoplayer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 入库后的视频元数据。播放只认 [localPath]，[sourceUri] 仅用于追溯来源
 * （相册 content:// 或 SAF 文档 Uri），不参与播放，源文件删掉也不影响播放。
 */
@Entity(
    tableName = "videos",
    indices = [Index(value = ["fileName", "size", "lastModified"], unique = true)]
)
data class VideoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 原始文件名，用于展示与去重 */
    val fileName: String,
    /** 入库后的绝对路径（私有目录里的副本） */
    val localPath: String,
    val size: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    /** 帧率，探测不到时为 0 */
    val frameRate: Float,
    /** 码率 bps，探测不到时为 0 */
    val bitRate: Int,
    val mimeType: String,
    val videoCodec: String,
    val audioCodec: String,
    /** 原始来源，仅做记录 */
    val sourceUri: String,
    val importedAt: Long,
    val lastPlayedAt: Long = 0L,
    val playCount: Int = 0,
    /**
     * 上次播放到的位置（毫秒），用于续播。
     * 0 表示从头播；播到离结尾不足 3 秒时会被归零，避免「一打开就播完」。
     */
    val lastPositionMs: Long = 0L,
    /** 前 1MB 的 MD5，用来识别同内容不同名的重复文件 */
    val checksum: String = "",
    /** 源文件的修改时间，参与去重键 */
    val lastModified: Long = 0L,
    /** READY 可播放；SOURCE_LOST 迁移时源已失效，保留条目供用户删除 */
    val status: String = Status.READY
) {
    val isPlayable: Boolean get() = status == Status.READY

    /**
     * 分辨率归类：按**短边**算，避免 1080x1920 的竖屏视频被误标成 1920p。
     * 2160 短边单独显示为 4K。
     */
    val resolutionLabel: String
        get() {
            if (width <= 0 || height <= 0) return "未知分辨率"
            val short = minOf(width, height)
            return when {
                short >= 2160 -> "4K"
                short >= 1440 -> "1440p"
                short >= 1080 -> "1080p"
                short >= 720 -> "720p"
                short >= 480 -> "480p"
                short >= 360 -> "360p"
                else -> "${short}p"
            }
        }

    /** 完整分辨率，例如 1920×1080，探测失败时显示未知 */
    val resolutionText: String
        get() = if (width > 0 && height > 0) "$width×$height" else "未知"

    object Status {
        const val READY = "READY"
        const val SOURCE_LOST = "SOURCE_LOST"
    }
}
