package com.example.videoplayer.data

import android.content.Context
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 可播放性校验：两级。
 *
 * 第一级 [quickCheck] 只看 MediaStore 已经查回来的元数据，纯内存计算、不碰文件，
 * 所以可以在主线程对整屏数据批量跑；这里不需要 MediaMetadataRetriever，
 * 也就天然不会因为大量取帧导致 ANR / OOM。
 *
 * 第二级 [deepCheck] 才真正打开文件：MediaExtractor 读轨道 -> MediaCodecList 找解码器。
 * 解码探测会同时占用解码器资源，所以用 [probeGate] 把并发压在 4 个以内。
 */
object PlayabilityChecker {

    private const val MAX_CONCURRENT_PROBES = 4
    private val probeGate = Semaphore(MAX_CONCURRENT_PROBES)

    fun quickCheck(video: GalleryVideo): QuickVerdict = when {
        !video.mimeType.startsWith("video/") -> QuickVerdict.BROKEN
        video.sizeBytes <= 0L -> QuickVerdict.BROKEN
        video.durationMs <= 0L -> QuickVerdict.BROKEN
        video.width <= 0 || video.height <= 0 -> QuickVerdict.SUSPICIOUS
        else -> QuickVerdict.OK
    }

    /**
     * 第二级校验。结果会写进 [PlayabilityCache]，
     * 同一文件（uri + 大小 + 修改时间都没变）第二次直接返回缓存。
     */
    /** 相册列表里的一条视频：先跑第一级，再做第二级。 */
    suspend fun deepCheck(context: Context, video: GalleryVideo): Playability =
        deepCheck(
            context = context,
            uri = video.uri,
            cacheKey = video.cacheKey,
            quick = quickCheck(video)
        )

    /**
     * 通用入口。
     *
     * SAF 选进来的文件没有 MediaStore 行，只有 Uri 和元数据，
     * 所以缓存键由调用方自己拼（一般是 "uri|size"）。
     */
    suspend fun deepCheck(
        context: Context,
        uri: Uri,
        cacheKey: String,
        quick: QuickVerdict = QuickVerdict.OK
    ): Playability = withContext(Dispatchers.IO) {
        val cached = PlayabilityCache.get(cacheKey)
        if (cached != null) return@withContext cached

        val verdict = if (quick == QuickVerdict.BROKEN) {
            Playability.Unsupported(
                reason = "视频文件不完整",
                detail = "元数据异常：$cacheKey"
            )
        } else {
            probeGate.withPermit { probe(context, uri) }
        }
        PlayabilityCache.put(cacheKey, verdict)
        verdict
    }

    private fun probe(context: Context, uri: Uri): Playability {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor().apply {
                setDataSource(context, uri, null)
            }

            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            var videoMime: String? = null
            var audioMime: String? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index) ?: continue
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                when {
                    mime.startsWith("video/") && videoFormat == null -> {
                        videoFormat = format
                        videoMime = mime
                    }

                    mime.startsWith("audio/") && audioFormat == null -> {
                        audioFormat = format
                        audioMime = mime
                    }
                }
            }

            val format = videoFormat
            if (format == null) {
                return Playability.Unsupported(
                    reason = "文件里没有视频画面",
                    detail = "找不到 video/* 轨道，可能是纯音频文件或已经损坏"
                )
            }

            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val videoDecoderMissing = runCatching {
                codecList.findDecoderForFormat(format)
            }.getOrNull() == null

            if (videoDecoderMissing) {
                return Playability.Unsupported(
                    reason = "该视频编码格式（${codecFriendlyName(videoMime)}）当前设备不支持",
                    detail = "MediaCodecList 找不到能解码 ${videoMime ?: "未知视频编码"} 的解码器"
                )
            }

            val audioDecoderMissing = if (audioFormat != null) {
                runCatching { codecList.findDecoderForFormat(audioFormat) }.getOrNull() == null
            } else {
                false
            }

            if (audioDecoderMissing) {
                Playability.SilentOnly(videoMime = videoMime.orEmpty(), audioMime = audioMime)
            } else {
                Playability.Supported(videoMime = videoMime.orEmpty(), audioMime = audioMime)
            }
        } catch (e: IOException) {
            Playability.Unsupported(
                reason = "视频文件已损坏或无法读取",
                detail = "MediaExtractor.setDataSource 失败：${e.message}"
            )
        } catch (e: SecurityException) {
            Playability.Unsupported(
                reason = "没有读取这个视频的权限",
                detail = "SecurityException：${e.message}"
            )
        } catch (e: RuntimeException) {
            // 个别机型在解析异常封装时会扔 IllegalStateException / IllegalArgumentException
            Playability.Unsupported(
                reason = "无法解析这个视频文件",
                detail = "${e.javaClass.simpleName}：${e.message}"
            )
        } finally {
            runCatching { extractor?.release() }
        }
    }

    /** 把 video/hevc 之类的 mime 翻译成用户认识的叫法。 */
    private fun codecFriendlyName(mime: String?): String = when (mime) {
        "video/hevc", "video/h265" -> "HEVC / H.265"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/avc", "video/h264" -> "H.264"
        "video/mp4v-es" -> "MPEG-4"
        "video/mpeg2" -> "MPEG-2"
        else -> mime?.removePrefix("video/")?.uppercase() ?: "未知编码"
    }
}
