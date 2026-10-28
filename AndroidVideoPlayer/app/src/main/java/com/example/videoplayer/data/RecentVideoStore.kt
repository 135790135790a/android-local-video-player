package com.example.videoplayer.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 最近打开的 5 个视频，使用 SharedPreferences 存储（不额外引入 DataStore 依赖）。
 * 存储内容为 JSON 数组，按 lastPlayedAt 倒序。
 */
class RecentVideoStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<RecentVideo> {
        val raw = prefs.getString(KEY_VIDEOS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val uri = obj.optString("uri")
                    if (uri.isBlank()) continue
                    add(
                        RecentVideo(
                            uri = uri,
                            displayName = obj.optString("name", "未命名视频"),
                            sizeBytes = obj.optLong("size", 0L),
                            durationMs = obj.optLong("duration", 0L),
                            lastPlayedAt = obj.optLong("playedAt", 0L)
                        )
                    )
                }
            }
        }.getOrDefault(emptyList()).distinctBy { it.uri }
    }

    /** 插入或更新一条记录，并只保留最近 [MAX_ITEMS] 条。 */
    fun upsert(video: RecentVideo, maxItems: Int = MAX_ITEMS): List<RecentVideo> {
        val updated = buildList {
            add(video)
            addAll(load().filterNot { it.uri == video.uri })
        }.sortedByDescending { it.lastPlayedAt }.take(maxItems)
        save(updated)
        return updated
    }

    fun remove(uri: String): List<RecentVideo> {
        val updated = load().filterNot { it.uri == uri }
        save(updated)
        return updated
    }

    fun clear() {
        prefs.edit().remove(KEY_VIDEOS).apply()
    }

    private fun save(items: List<RecentVideo>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("uri", item.uri)
                    put("name", item.displayName)
                    put("size", item.sizeBytes)
                    put("duration", item.durationMs)
                    put("playedAt", item.lastPlayedAt)
                }
            )
        }
        prefs.edit().putString(KEY_VIDEOS, array.toString()).apply()
    }

    companion object {
        const val MAX_ITEMS = 5
        private const val PREFS_NAME = "recent_videos"
        private const val KEY_VIDEOS = "videos"
    }
}
