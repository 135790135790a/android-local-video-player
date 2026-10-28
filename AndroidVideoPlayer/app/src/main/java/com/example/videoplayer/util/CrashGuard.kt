package com.example.videoplayer.util

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 全局崩溃兜底：把未捕获异常的堆栈写进应用私有目录，方便事后定位。
 *
 * 这里刻意「不吞异常」：
 * 1. 在 UncaughtExceptionHandler 里吞掉异常会让进程停在不一致状态，用户看到的是黑屏 / 无响应，
 *    比正常退出体验更差，还会丢失真实原因；
 * 2. 主线程已经处于半损坏状态，继续渲染没有任何保证。
 *
 * 所以本类只做「留证据 + 交回系统默认处理器」，同时在下次启动时给用户一句提示：
 * 日志已经保存。真正的稳定性靠上游把崩溃点修掉。
 */
object CrashGuard {

    private const val TAG = "CrashGuard"
    private const val PREFS = "crash_guard"
    private const val KEY_PENDING = "pending_crash"
    private const val DIR_NAME = "crash"
    private const val MAX_FILES = 5

    /** 在 Application / Activity 创建时调用一次，重复调用无副作用。 */
    fun install(context: Context) {
        val app = context.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app, current))
    }

    /** 上次运行是否发生过未捕获异常；读取后清除标记。 */
    fun consumePendingCrash(context: Context): Boolean {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return false
        prefs.edit().putBoolean(KEY_PENDING, false).apply()
        return true
    }

    /** 崩溃日志目录：/data/data/<pkg>/files/crash。 */
    fun crashDir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME)

    private class Handler(
        private val app: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            writeReport(thread, throwable)
            // 交回系统默认处理（通常是记录 + 通知 AMS + 杀进程），不吞异常
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }

        @Suppress("ApplySharedPref")
        private fun writeReport(thread: Thread, throwable: Throwable) {
            runCatching {
                val dir = crashDir(app)
                if (!dir.exists()) dir.mkdirs()

                // 只保留最近 MAX_FILES 份，避免长期使用塞满私有目录
                dir.listFiles()?.let { files ->
                    val overflow = files.size - MAX_FILES + 1
                    if (overflow > 0) {
                        files.sortedBy { it.lastModified() }
                            .take(overflow)
                            .forEach { it.delete() }
                    }
                }

                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val file = File(dir, "crash-$stamp.log")
                file.writeText(buildReport(thread, throwable))

                // 进程马上就要结束，必须同步落盘
                app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_PENDING, true)
                    .commit()
                Log.e(TAG, "crash report saved: ${file.absolutePath}", throwable)
            }
        }

        private fun buildReport(thread: Thread, throwable: Throwable): String = buildString {
            appendLine("time=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            appendLine("thread=${thread.name}")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("app=${app.packageName}")
            appendLine()
            append(Log.getStackTraceString(throwable))
        }
    }
}
