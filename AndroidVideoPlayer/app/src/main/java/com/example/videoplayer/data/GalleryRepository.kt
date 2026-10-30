package com.example.videoplayer.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 相册视频的查询层。
 *
 * 只查 MediaStore.Video，不读文件内容；所有 query 都跑在 Dispatchers.IO。
 * 列索引一律用 [android.database.Cursor.getColumnIndex] 并且做 -1 兜底，
 * 有些定制 ROM 的 WIDTH/HEIGHT 列会缺失，用 getColumnIndexOrThrow 会直接崩。
 */
class GalleryRepository(context: Context) {

    private val appContext = context.applicationContext

    suspend fun loadVideos(): List<GalleryVideo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        val selection = "${MediaStore.Video.Media.SIZE} > 0 AND " +
            "${MediaStore.Video.Media.DURATION} > 0"
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val result = ArrayList<GalleryVideo>()
        runCatching {
            appContext.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                val addedIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                val mimeIndex = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)
                val widthIndex = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightIndex = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)

                while (cursor.moveToNext()) {
                    val id = if (idIndex >= 0) cursor.getLong(idIndex) else continue
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    val duration = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L
                    // 双保险：selection 已经过滤过，这里再挡一次异常行
                    if (size <= 0L || duration <= 0L) continue

                    result += GalleryVideo(
                        id = id,
                        uri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        ),
                        displayName = if (nameIndex >= 0) {
                            cursor.getString(nameIndex).orEmpty().ifBlank { "未命名视频" }
                        } else {
                            "未命名视频"
                        },
                        durationMs = duration,
                        sizeBytes = size,
                        dateAddedSeconds = if (addedIndex >= 0) cursor.getLong(addedIndex) else 0L,
                        dateModifiedSeconds = if (modifiedIndex >= 0) {
                            cursor.getLong(modifiedIndex)
                        } else {
                            0L
                        },
                        mimeType = if (mimeIndex >= 0) {
                            cursor.getString(mimeIndex).orEmpty()
                        } else {
                            "video/*"
                        },
                        width = if (widthIndex >= 0) cursor.getInt(widthIndex) else 0,
                        height = if (heightIndex >= 0) cursor.getInt(heightIndex) else 0
                    )
                }
            }
        }
        result
    }
}
