package com.example.videoplayer.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.videoplayer.data.MediaLibrary
import com.example.videoplayer.data.VideoMetaReader
import com.example.videoplayer.data.VideoProbe
import com.example.videoplayer.data.db.AppDatabase
import com.example.videoplayer.data.db.VideoEntity
import java.io.File
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 视频入库任务：复制 → 指纹判重 → 探测元数据 → 写 Room。
 *
 * 每条视频独立成败，单条失败不阻断整批；任务取消时立即停止并保留已入库的部分。
 * 进度通过 [setProgress] 上报「第 i / N 个 + 当前文件名」。
 */
class ImportWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val uris = inputData.getStringArray(KEY_URIS)?.toList().orEmpty()
        if (uris.isEmpty()) return Result.success(summary(0, 0, 0))

        val context = applicationContext
        val dao = AppDatabase.get(context).videoDao()

        var done = 0
        var failed = 0
        var skipped = 0
        var currentName = ""

        for ((index, raw) in uris.withIndex()) {
            if (isStopped) return Result.failure(summary(done, failed, skipped))

            val name = runCatching { VideoMetaReader.read(context, Uri.parse(raw)).displayName }
                .getOrDefault("")
            currentName = name
            setProgress(progress(index, uris.size, done, failed, currentName))

            val uri = runCatching { Uri.parse(raw) }.getOrNull()
            if (uri == null) {
                failed++
                continue
            }

            val meta = runCatching { VideoMetaReader.read(context, uri) }
                .getOrDefault(VideoMetaReader.Meta(name.ifBlank { "未命名视频" }, 0L, 0L))
            val lastModified = MediaLibrary.lastModified(context, uri)

            // 一重去重：同名 + 同大小 + 同修改时间，且本地副本还在
            val fingerprint = dao.findByFingerprint(meta.displayName, meta.sizeBytes, lastModified)
            if (fingerprint != null && File(fingerprint.localPath).exists()) {
                skipped++
                continue
            }

            val copied = MediaLibrary.copyToLibrary(context, uri, meta.displayName)
            if (copied == null) {
                failed++
                continue
            }

            // 二重去重：内容指纹相同（换了文件名、修改时间变了的同一段视频）
            val checksum = MediaLibrary.checksum(copied)
            if (checksum.isNotBlank()) {
                val duplicate = dao.findByChecksum(checksum)
                if (duplicate != null) {
                    MediaLibrary.deleteFile(copied.absolutePath)
                    skipped++
                    continue
                }
            }

            // 探测卡死的坏文件会让整个导入队列停住，这里给 20 秒上限，超时就按「探测不到」入库
            val info = withTimeoutOrNull(PROBE_TIMEOUT_MS) { VideoProbe.probeFile(copied) }
            val inserted = dao.insert(
                VideoEntity(
                    fileName = meta.displayName,
                    localPath = copied.absolutePath,
                    size = copied.length(),
                    durationMs = info?.durationMs?.takeIf { it > 0L } ?: meta.durationMs,
                    width = info?.width ?: 0,
                    height = info?.height ?: 0,
                    frameRate = info?.frameRate ?: 0f,
                    bitRate = info?.bitRate ?: 0,
                    mimeType = info?.videoMime.orEmpty().ifBlank { "video/*" },
                    videoCodec = info?.videoCodec.orEmpty(),
                    audioCodec = info?.audioCodec.orEmpty(),
                    sourceUri = raw,
                    importedAt = System.currentTimeMillis(),
                    checksum = checksum,
                    lastModified = lastModified,
                    status = VideoEntity.Status.READY
                )
            )
            if (inserted == -1L) {
                MediaLibrary.deleteFile(copied.absolutePath)
                skipped++
            } else {
                done++
            }
        }

        setProgress(progress(uris.size, uris.size, done, failed, ""))
        return Result.success(summary(done, failed, skipped))
    }

    private fun progress(index: Int, total: Int, done: Int, failed: Int, name: String) = workDataOf(
        PROGRESS_CURRENT to index,
        PROGRESS_TOTAL to total,
        PROGRESS_DONE to done,
        PROGRESS_FAILED to failed,
        PROGRESS_NAME to name
    )

    private fun summary(done: Int, failed: Int, skipped: Int) = workDataOf(
        RESULT_DONE to done,
        RESULT_FAILED to failed,
        RESULT_SKIPPED to skipped
    )

    companion object {
        const val UNIQUE_WORK = "import_videos"
        const val TAG = "import_videos"

        const val KEY_URIS = "uris"

        const val PROGRESS_CURRENT = "progress_current"
        const val PROGRESS_TOTAL = "progress_total"
        const val PROGRESS_DONE = "progress_done"
        const val PROGRESS_FAILED = "progress_failed"
        const val PROGRESS_NAME = "progress_name"

        const val RESULT_DONE = "result_done"
        const val RESULT_FAILED = "result_failed"
        const val RESULT_SKIPPED = "result_skipped"

        /** 单个文件的元数据探测上限，防止坏文件把导入队列卡住 */
        private const val PROBE_TIMEOUT_MS = 20_000L
    }
}
