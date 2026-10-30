package com.example.videoplayer.util

import android.content.Context
import coil.ImageLoader
import coil.decode.VideoFrameDecoder

/**
 * 全局单例 ImageLoader。
 *
 * 媒体库里存的是 mp4/mkv 这类视频文件，要让 Coil 从视频里抽出一帧当封面，
 * 必须显式注册 [VideoFrameDecoder]，否则默认 ImageLoader 只会尝试当静态图解码、加载失败。
 */
object AppImageLoader {

    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        return instance ?: synchronized(this) {
            instance ?: ImageLoader.Builder(context.applicationContext)
                .components { add(VideoFrameDecoder.Factory()) }
                .crossfade(true)
                .build()
                .also { instance = it }
        }
    }
}
