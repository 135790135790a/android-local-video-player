package com.example.videoplayer.data

import android.content.Context
import android.net.Uri
import com.example.videoplayer.data.db.AppDatabase
import com.example.videoplayer.data.db.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.0.1 → v1.0.2 的数据迁移：把「最近播放」里的 SAF Uri 也复制进媒体库。
 *
 * Uri 已失效的（文件被删、授权丢失）不丢弃，而是写一条 SOURCE_LOST 记录，
 * 让用户能在媒体库里看到「源文件已丢失」并手动删除 —— 用户要求「不要悄悄丢掉数据」。
 * 迁移成功后才清空旧列表；中途失败下次启动会重试，靠指纹去重避免重复复制。
 */
object LegacyMigrator {

    private const val PREFS_NAME = "media_library"
    private const val KEY_MIGRATED = "legacy_v101_migrated"

    suspend fun migrateIfNeeded(context: Context): Int = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_MIGRATED, false)) return@withContext 0

        val legacy = runCatching { RecentVideoStore(context).load() }.getOrDefault(emptyList())
        if (legacy.isEmpty()) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return@withContext 0
        }

        val dao = AppDatabase.get(context).videoDao()
        var migrated = 0

        legacy.forEach { item ->
            val uri = runCatching { Uri.parse(item.uri) }.getOrNull()
            val readable = uri != null && runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)

            if (!readable) {
                dao.insert(
                    VideoEntity(
                        fileName = item.displayName.ifBlank { "已丢失的视频" },
                        localPath = "",
                        size = item.sizeBytes,
                        durationMs = item.durationMs,
                        width = 0,
                        height = 0,
                        frameRate = 0f,
                        bitRate = 0,
                        mimeType = "",
                        videoCodec = "",
                        audioCodec = "",
                        sourceUri = item.uri,
                        importedAt = System.currentTimeMillis(),
                        status = VideoEntity.Status.SOURCE_LOST
                    )
                )
                return@forEach
            }

            val copied = MediaLibrary.copyToLibrary(context, uri!!, item.displayName)
            if (copied == null) return@forEach

            val checksum = MediaLibrary.checksum(copied)
            if (checksum.isNotBlank() && dao.findByChecksum(checksum) != null) {
                MediaLibrary.deleteFile(copied.absolutePath)
                return@forEach
            }

            val info = VideoProbe.probeFile(copied)
            val inserted = dao.insert(
                VideoEntity(
                    fileName = item.displayName,
                    localPath = copied.absolutePath,
                    size = copied.length(),
                    durationMs = info?.durationMs?.takeIf { it > 0L } ?: item.durationMs,
                    width = info?.width ?: 0,
                    height = info?.height ?: 0,
                    frameRate = info?.frameRate ?: 0f,
                    bitRate = info?.bitRate ?: 0,
                    mimeType = info?.videoMime.orEmpty().ifBlank { "video/*" },
                    videoCodec = info?.videoCodec.orEmpty(),
                    audioCodec = info?.audioCodec.orEmpty(),
                    sourceUri = item.uri,
                    importedAt = System.currentTimeMillis(),
                    checksum = checksum,
                    status = VideoEntity.Status.READY
                )
            )
            if (inserted != -1L) migrated++
        }

        prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        // 迁移完成，旧列表不再需要
        runCatching { RecentVideoStore(context).clear() }
        migrated
    }
}
