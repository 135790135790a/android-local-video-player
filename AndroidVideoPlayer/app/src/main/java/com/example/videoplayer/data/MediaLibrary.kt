package com.example.videoplayer.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 媒体库的本地存储管理。
 *
 * 所有入库视频都复制到应用私有目录 `getExternalFilesDir(DIRECTORY_MOVIES)/library/`，
 * 该目录随应用卸载一并清除；外部存储不可用时降级到 `filesDir/library/`。
 * 播放永远读这里的 File，不再回读相册 Uri。
 */
object MediaLibrary {

    private const val DIR_NAME = "library"
    private const val CHECKSUM_BYTES = 1024L * 1024L
    private const val SPACE_RESERVE_BYTES = 32L * 1024L * 1024L

    fun dir(context: Context): File {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val base = external ?: File(context.filesDir, "movies")
        return File(base, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    fun usedBytes(context: Context): Long =
        dir(context).listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    fun freeBytes(context: Context): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    fun hasSpace(context: Context, needBytes: Long): Boolean {
        val free = freeBytes(context)
        if (free == Long.MAX_VALUE) return true
        return free > needBytes + SPACE_RESERVE_BYTES
    }

    /** 同名文件自动加 (1)(2) 后缀，不覆盖已有文件 */
    fun uniqueFile(dir: File, fileName: String): File {
        val safeName = fileName.ifBlank { "video_${System.currentTimeMillis()}.mp4" }
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
        val dot = safeName.lastIndexOf('.')
        val base = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext = if (dot > 0) safeName.substring(dot) else ""
        var candidate = File(dir, safeName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base($index)$ext")
            index++
        }
        return candidate
    }

    /**
     * 把来源复制进媒体库。
     *
     * @param onProgress 0f..1f，总大小未知时不回调
     * @return 写入的文件；空间不足、读取失败、0 字节一律返回 null（已清理半截文件）
     */
    suspend fun copyToLibrary(
        context: Context,
        uri: Uri,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val target = uniqueFile(dir(context), fileName)
        val declaredSize = querySize(context, uri)
        if (declaredSize > 0 && !hasSpace(context, declaredSize)) {
            return@withContext null
        }

        var copied = 0L
        val ok = runCatching {
            val input = context.contentResolver.openInputStream(uri)
                ?: return@runCatching false
            input.use { source ->
                FileOutputStream(target).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = source.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (declaredSize > 0) {
                            onProgress((copied.toFloat() / declaredSize).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)

        if (!ok || target.length() <= 0L) {
            runCatching { target.delete() }
            return@withContext null
        }
        target
    }

    /** 前 1MB 的 MD5，够用来做重名不同内容的重复识别，又不用读完整个大文件 */
    fun checksum(file: File): String = runCatching {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = CHECKSUM_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    fun deleteFile(path: String): Boolean = runCatching {
        val file = File(path)
        !file.exists() || file.delete()
    }.getOrDefault(false)

    /**
     * 源文件的修改时间，参与去重键。
     *
     * SAF 文档走 DocumentsContract 的 COLUMN_LAST_MODIFIED（毫秒），
     * MediaStore 走 DATE_MODIFIED（秒）需要乘 1000。两者都拿不到时返回 0。
     */
    @Suppress("DEPRECATION")
    fun lastModified(context: Context, uri: Uri): Long {
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (index >= 0 && cursor.moveToFirst()) {
                    val value = cursor.getLong(index)
                    if (value > 0L) return value
                }
            }
        } catch (_: Exception) {
            // 部分 provider 不支持无 projection 查询，继续走 MediaStore 分支
        }
        try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATE_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                if (index >= 0 && cursor.moveToFirst()) {
                    val seconds = cursor.getLong(index)
                    if (seconds > 0L) return seconds * 1000L
                }
            }
        } catch (_: Exception) {
            // 忽略
        }
        return 0L
    }

    private fun querySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else 0L
            } ?: 0L
    }.getOrDefault(0L)
}
