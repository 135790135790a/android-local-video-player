package com.example.videoplayer.data

/** 第一级静态校验的结论。 */
enum class QuickVerdict {
    /** 元数据看起来正常。 */
    OK,

    /** 元数据有缺项（宽高为 0/-1 等），可能实际播不了，需要第二级确认。 */
    SUSPICIOUS,

    /** 元数据自相矛盾（非 video 类型、0 字节、时长为 0），基本可以判定坏文件。 */
    BROKEN
}

/** 单条视频的可播放性结论。 */
sealed interface Playability {

    /** 视频轨和音频轨都有解码器，可以正常播放。 */
    data class Supported(val videoMime: String, val audioMime: String?) : Playability

    /**
     * 视频能解码但音频不能：允许播放，但需要静音，
     * UI 上给一句「音频格式不支持，将静音播放」的提示。
     */
    data class SilentOnly(val videoMime: String, val audioMime: String?) : Playability

    /** 不可播放。[reason] 是给用户看的一句话，[detail] 是给排查用的技术细节。 */
    data class Unsupported(val reason: String, val detail: String) : Playability

    val isPlayable: Boolean get() = this !is Unsupported
}

/**
 * 校验结果的内存缓存。
 *
 * 用带 accessOrder 的 LinkedHashMap 做一个简单的 LRU：
 * 相册最多也就几十上百条，200 条的上限足够，超出后淘汰最久未用的。
 */
object PlayabilityCache {

    private const val MAX_ENTRIES = 200

    private val entries = object : LinkedHashMap<String, Playability>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Playability>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): Playability? = entries[key]

    @Synchronized
    fun put(key: String, value: Playability) {
        entries[key] = value
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
