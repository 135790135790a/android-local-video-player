package com.example.videoplayer.data

/**
 * 用户通过 SAF 选择过的视频。uri 是已经 takePersistableUriPermission 的 content:// 地址，
 * 应用重启后依然可读。
 */
data class RecentVideo(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long = 0L,
    val durationMs: Long = 0L,
    val lastPlayedAt: Long = 0L
)
