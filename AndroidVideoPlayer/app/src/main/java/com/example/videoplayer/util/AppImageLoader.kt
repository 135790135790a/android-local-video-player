package com.example.videoplayer.util

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache

/**
 * 全局单例 ImageLoader。
 *
 * 媒体库里存的是 mp4/mkv 这类视频文件，要让 Coil 从视频里抽出一帧当封面，
 * 必须显式注册 [VideoFrameDecoder]，否则默认 ImageLoader 只会尝试当静态图解码、加载失败。
 *
 * 磁盘缓存显式放大到 5%：视频抽帧比解静态图贵得多（每个缩略图都要解一路视频），
 * 落到磁盘后，第二次进媒体库几乎不用再抽帧，滚动明显更顺。
 */
object AppImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components { add(VideoFrameDecoder.Factory()) }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache"))
                        .maxSizePercent(0.05)
                        .build()
                }
                .crossfade(true)
                .build()
                .also { instance = it }
        }
    }
}
