package com.example.videoplayer.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/**
 * 通过 ContentResolver 查询 SAF 返回的 content:// Uri 的文件名、大小与时长，
 * 全程不需要任何存储权限。
 */
object VideoMetaReader {

    data class Meta(
        val displayName: String,
        val sizeBytes: Long,
        val durationMs: Long
    )

    fun read(context: Context, uri: Uri): Meta {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "未命名视频"
        var size = 0L
        var duration = 0L

        val projection = arrayOf(
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE,
            MediaStore.MediaColumns.DURATION
        )

        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let {
                        cursor.getString(it)?.takeIf(String::isNotBlank)?.let { value -> name = value }
                    }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let {
                        size = cursor.getLong(it).coerceAtLeast(0L)
                    }
                    cursor.getColumnIndex(MediaStore.MediaColumns.DURATION).takeIf { it >= 0 }?.let {
                        duration = cursor.getLong(it).coerceAtLeast(0L)
                    }
                }
            }
        }

        // 少数 DocumentsProvider 不支持 DURATION 列，退化为 0，播放器加载后再回填。
        if (size == 0L) {
            size = querySizeFallback(context.contentResolver, uri)
        }
        return Meta(displayName = name, sizeBytes = size, durationMs = duration)
    }

    private fun querySizeFallback(resolver: ContentResolver, uri: Uri): Long = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { it.length.coerceAtLeast(0L) } ?: 0L
    }.getOrDefault(0L)
}
