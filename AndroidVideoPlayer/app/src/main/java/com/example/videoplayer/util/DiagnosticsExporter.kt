package com.example.videoplayer.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import android.util.Log
import androidx.core.content.FileProvider
import com.example.videoplayer.data.MediaLibrary
import com.example.videoplayer.data.db.AppDatabase
import com.example.videoplayer.data.db.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 导出诊断包：设备信息 + 媒体库概览 + 崩溃日志打成 zip，方便用户反馈问题时一并附上。
 * 只含统计信息和日志，不含任何视频文件内容。
 */
object DiagnosticsExporter {

    private const val TAG = "DiagnosticsExporter"
    private const val CRASH_DIR = "crash"
    private const val MAX_CRASH_LOGS = 5

    /** 生成诊断包；失败返回 null，由调用方提示。 */
    suspend fun export(context: Context): File? = withContext(Dispatchers.IO) {
        runCatching {
            val zipFile = File(
                context.cacheDir,
                "diagnostics-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.zip",
            )
            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                putText(zip, "device.txt", deviceInfo(context))
                putText(zip, "library.txt", libraryInfo(context))
                appendCrashLogs(context, zip)
                putText(zip, "README.txt", README)
            }
            zipFile
        }.onFailure { Log.w(TAG, "export diagnostics failed", it) }.getOrNull()
    }

    /** 分享诊断包（走 FileProvider，不暴露真实路径）。 */
    fun shareIntent(context: Context, file: File): Intent? = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }.onFailure { Log.w(TAG, "build share intent failed", it) }.getOrNull()

    private fun deviceInfo(context: Context): String = buildString {
        val pm = context.packageManager
        val pkg = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        appendLine("app_name=${runCatching { context.getString(context.applicationInfo.labelRes) }.getOrDefault("?")}")
        appendLine("package=${context.packageName}")
        appendLine("version=${pkg?.versionName} (${pkg?.longVersionCode ?: 0})")
        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("model=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("device=${Build.DEVICE} / product=${Build.PRODUCT}")
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("locale=${Locale.getDefault()}")
        appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
        appendLine("library_dir=${runCatching { MediaLibrary.dir(context).absolutePath }.getOrDefault("?")}")
        appendLine("free_bytes=${runCatching { MediaLibrary.freeBytes(context) }.getOrDefault(0L)}")
        runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            appendLine("internal_total_bytes=${stat.totalBytes}")
        }
    }

    private suspend fun libraryInfo(context: Context): String = runCatching {
        val dao = AppDatabase.get(context).videoDao()
        val all = dao.loadAll()
        buildString {
            appendLine("count=${all.size}")
            appendLine("total_bytes=${all.sumOf { it.size }}")
            appendLine("durations_ms=${all.sumOf { it.durationMs }}")
            appendLine("ready=${all.count { it.status == VideoEntity.Status.READY }}")
            appendLine("lost=${all.count { it.status == VideoEntity.Status.SOURCE_LOST }}")
            appendLine()
            appendLine("# 每行：状态 | 分辨率 | 时长 | 大小 | 编码 | 文件名")
            all.take(200).forEach { v ->
                appendLine(
                    listOf(
                        v.status,
                        v.resolutionLabel,
                        Formatters.duration(v.durationMs),
                        Formatters.bytes(v.size),
                        v.videoCodec.ifBlank { "?" },
                        v.fileName,
                    ).joinToString(" | "),
                )
            }
        }
    }.getOrElse { "query library failed: ${it.javaClass.simpleName}: ${it.message}" }

    private fun appendCrashLogs(context: Context, zip: ZipOutputStream) {
        val dir = File(context.filesDir, CRASH_DIR)
        val logs = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(MAX_CRASH_LOGS)
            .orEmpty()
        if (logs.isEmpty()) {
            putText(zip, "crash/none.txt", "没有崩溃日志。")
            return
        }
        logs.forEachIndexed { index, file ->
            runCatching {
                zip.putNextEntry(ZipEntry("crash/${index + 1}-${file.name}"))
                zip.write(file.readText().toByteArray())
                zip.closeEntry()
            }
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        runCatching {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }

    private val README = """
        诊断包内容：
        - device.txt    设备与安装信息
        - library.txt   媒体库统计与视频清单（前 200 条）
        - crash/        最近的崩溃日志（最多 5 份）

        不含视频文件本身。
    """.trimIndent()
}
