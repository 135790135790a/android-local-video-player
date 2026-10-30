package com.example.videoplayer.data

import android.net.Uri

/**
 * 相册里的一条视频（来自 MediaStore 的一次查询结果）。
 *
 * [cacheKey] 用于可播放性校验结果的内存缓存：同一个文件被改名/移动后
 * 只要大小和修改时间没变，仍然命中缓存，不必重复打开文件探测解码器。
 */
data class GalleryVideo(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val mimeType: String,
    val width: Int,
    val height: Int
) {
    val cacheKey: String get() = "$uri|$sizeBytes|$dateModifiedSeconds"

    /** 例如 "1920×1080"，宽高未知时为空串。 */
    val resolutionLabel: String
        get() = if (width > 0 && height > 0) "${width}×$height" else ""
}
