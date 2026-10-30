package com.example.videoplayer.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 媒体库用到的展示格式化，统一放一处，避免各页面各写一套 */
object Formatters {

    fun bytes(bytes: Long): String = when {
        bytes <= 0L -> "0 B"
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
    }

    fun bitRate(bps: Int): String = when {
        bps <= 0 -> "未知"
        bps < 1_000_000 -> "${bps / 1000} kbps"
        else -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000f)
    }

    fun frameRate(fps: Float): String = when {
        fps <= 0f -> "未知"
        fps % 1f == 0f -> "${fps.toInt()} fps"
        else -> String.format(Locale.US, "%.2f fps", fps)
    }

    fun dateTime(millis: Long): String = if (millis <= 0L) {
        "—"
    } else {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        }.getOrDefault("—")
    }

    fun duration(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L) / 1000L).toInt()
        val hours = totalSeconds / 3600
        val minutes = totalSeconds % 3600 / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
