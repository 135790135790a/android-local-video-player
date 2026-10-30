package com.example.videoplayer.data

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 读取视频的技术元数据：分辨率、帧率、码率、编码格式。
 *
 * 用 MediaExtractor 拿轨道 MIME（编码格式最准），用 MediaMetadataRetriever 拿
 * 显示宽高与时长（对旋转、变形容器的处理更稳）。任一失败都返回 null 降级，
 * 不抛异常、不阻塞主线程。
 *
 * 入库时统一用 [probeFile] 读媒体库里的副本，避免反复走 SAF 授权。
 */
object VideoProbe {

    data class Info(
        val width: Int = 0,
        val height: Int = 0,
        val rotation: Int = 0,
        val frameRate: Float = 0f,
        val bitRate: Int = 0,
        val durationMs: Long = 0L,
        val videoMime: String = "",
        val audioMime: String = "",
        val videoCodec: String = "",
        val audioCodec: String = ""
    )

    suspend fun probe(context: Context, uri: Uri): Info? = probeWith(
        prepareExtractor = { it.setDataSource(context, uri, null) },
        prepareRetriever = { it.setDataSource(context, uri) }
    )

    suspend fun probeFile(file: File): Info? = probeWith(
        prepareExtractor = { it.setDataSource(file.absolutePath) },
        prepareRetriever = { it.setDataSource(file.absolutePath) }
    )

    private suspend fun probeWith(
        prepareExtractor: (MediaExtractor) -> Unit,
        prepareRetriever: (MediaMetadataRetriever) -> Unit
    ): Info? = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var retriever: MediaMetadataRetriever? = null
        try {
            var videoMime = ""
            var audioMime = ""
            var bitRate = 0

            extractor = MediaExtractor()
            prepareExtractor(extractor)
            for (index in 0 until extractor.trackCount) {
                val format = runCatching { extractor.getTrackFormat(index) }.getOrNull() ?: continue
                val mime = runCatching { format.getString(MediaFormat.KEY_MIME) }.getOrNull().orEmpty()
                when {
                    mime.startsWith("video/") && videoMime.isEmpty() -> {
                        videoMime = mime
                        bitRate = intOrZero(format, MediaFormat.KEY_BIT_RATE)
                    }
                    mime.startsWith("audio/") && audioMime.isEmpty() -> {
                        audioMime = mime
                    }
                }
            }

            retriever = MediaMetadataRetriever()
            prepareRetriever(retriever)
            val width = metaInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = metaInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val rotation = metaInt(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val duration = metaLong(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION)
            val frameRate = metaLong(retriever, MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                .takeIf { it > 0 }
                ?.toFloat()
                ?: estimateFrameRate(retriever, duration)

            Info(
                width = width,
                height = height,
                rotation = rotation,
                frameRate = frameRate,
                bitRate = bitRate,
                durationMs = duration,
                videoMime = videoMime,
                audioMime = audioMime,
                videoCodec = describeCodec(videoMime),
                audioCodec = describeCodec(audioMime)
            )
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { extractor?.release() }
            runCatching { retriever?.release() }
        }
    }

    /** 拿不到 CAPTURE_FRAMERATE 时，用总帧数 / 时长估算 */
    private fun estimateFrameRate(retriever: MediaMetadataRetriever, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        val frames = metaLong(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            .takeIf { it > 0 } ?: return 0f
        return (frames * 1000f / durationMs).let { if (it.isFinite() && it in 1f..240f) it else 0f }
    }

    private fun metaInt(retriever: MediaMetadataRetriever, key: Int): Int =
        runCatching { retriever.extractMetadata(key)?.trim()?.toIntOrNull() ?: 0 }.getOrDefault(0)

    private fun metaLong(retriever: MediaMetadataRetriever, key: Int): Long =
        runCatching { retriever.extractMetadata(key)?.trim()?.toLongOrNull() ?: 0L }.getOrDefault(0L)

    private fun intOrZero(format: MediaFormat, key: String): Int = runCatching {
        if (format.containsKey(key)) format.getInteger(key) else 0
    }.getOrDefault(0)

    /** 把 MIME 翻译成用户能看懂的编码名 */
    fun describeCodec(mime: String): String = when {
        mime.isBlank() -> ""
        mime.contains("avc", true) || mime.contains("h264", true) -> "H.264 (AVC)"
        mime.contains("hevc", true) || mime.contains("h265", true) || mime.contains("hvc1", true) -> "H.265 (HEVC)"
        mime.contains("av01", true) || mime.contains("av1", true) -> "AV1"
        mime.contains("vp09", true) || mime.contains("vp9", true) -> "VP9"
        mime.contains("vp08", true) || mime.contains("vp8", true) -> "VP8"
        mime.contains("mpeg4", true) || mime.contains("mp4v", true) -> "MPEG-4"
        mime.contains("mpeg2", true) -> "MPEG-2"
        mime.contains("mp4a", true) || mime.contains("aac", true) -> "AAC"
        mime.contains("opus", true) -> "Opus"
        mime.contains("vorbis", true) -> "Vorbis"
        mime.contains("eac-3", true) || mime.contains("eac3", true) -> "E-AC-3"
        mime.contains("ac-3", true) || mime.contains("ac3", true) -> "AC-3"
        mime.contains("flac", true) -> "FLAC"
        mime.contains("mp3", true) || mime.contains("mpeg", true) -> "MP3"
        else -> mime.substringAfter('/').uppercase()
    }
}
