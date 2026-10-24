package com.pika.core.log

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 崩溃报告捕获与落盘。
 *
 * 背景：LogStore 是纯内存环形缓冲，进程崩溃即清零——用户复现闪退后重新进入，
 * 排障日志反而消失了，崩溃原因无从取证。本对象在进程内安装全局
 * UncaughtExceptionHandler：崩溃瞬间同步把「堆栈 + 应用版本 + 设备信息 +
 * LogStore 尾部 + 系统 crash 缓冲」写入 filesDir/crash/last_crash.txt，
 * 下次启动在日志页顶部呈现（查看/复制/忽略），崩溃证据得以跨进程存活。
 *
 * 约束：崩溃回调运行在垂死进程里，实现必须完全同步、快、且自身绝不能抛异常
 * （全部 try/catch 兜底），写完后再交还系统默认处理器走正常进程终止。
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val FILE_NAME = "last_crash.txt"

    @Volatile
    private var installed = false

    /** 在 Application.onCreate 最先调用：后续任何初始化阶段的崩溃都能被捕获。
     *  顺带检测上次崩溃遗留的报告，在 LogStore 里留一条指路日志。 */
    fun install(context: Context) {
        if (installed) return
        installed = true
        if (pendingReport(context) != null) {
            LogStore.log(
                TAG, "E",
                "检测到上次异常退出：崩溃报告已保存（含堆栈/版本/崩溃前日志），设置 → 调试日志 顶部可查看或复制",
            )
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeReport(context.applicationContext, thread, throwable)
            } catch (t: Throwable) {
                // 崩溃处理链绝不能再抛：吞掉自身故障，尽力交还系统默认处理
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    /** 上次崩溃的报告全文；无则 null（日志页横幅据此显示） */
    fun pendingReport(context: Context): String? {
        val f = file(context)
        return if (f.exists() && f.length() > 0L) runCatching { f.readText() }.getOrNull() else null
    }

    /** 用户点「忽略」后清除报告文件 */
    fun dismissReport(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context): File =
        File(File(context.filesDir, "crash").apply { mkdirs() }, FILE_NAME)

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val version = try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName} ($code)"
        } catch (t: Throwable) {
            "unknown"
        }
        val sb = StringBuilder()
        sb.appendLine("=== PiKA 崩溃报告 ===")
        sb.appendLine("时间: ${fmt.format(Date())}")
        sb.appendLine("版本: $version")
        sb.appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} · Android API ${Build.VERSION.SDK_INT}")
        sb.appendLine("线程: ${thread.name}${if (thread === Looper.getMainLooper().thread) "（主线程）" else ""}")
        sb.appendLine()
        sb.appendLine("--- 崩溃堆栈 ---")
        sb.appendLine(Log.getStackTraceString(throwable))
        sb.appendLine()
        sb.appendLine("--- 崩溃前应用日志（尾部） ---")
        sb.appendLine(runCatching { LogStore.allText() }.getOrDefault("(不可用)"))
        sb.appendLine()
        sb.appendLine("--- 系统 crash 缓冲（尽力而为，仅本应用可读部分） ---")
        sb.appendLine(logcatCrashBuffer().ifBlank { "(空)" })
        file(context).writeText(sb.toString())
    }

    /** 读取系统 crash 缓冲最近 300 行（无 READ_LOGS 权限时仅能拿到本应用进程的行，
     *  恰好包含我们自己的 FATAL EXCEPTION 堆栈；失败不致命）。 */
    private fun logcatCrashBuffer(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-b", "crash", "-t", "300")
            .redirectErrorStream(true)
            .start()
        p.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        ""
    }
}

/**
 * 主线程卡顿看门狗：每 4 秒向主线程投递心跳，3 秒内未到达即判定主线程阻塞，
 * 记录主线程当前调用栈到 LogStore（每次卡顿周期最多每 10s 记一条，防刷屏）。
 *
 * 用途：用户反馈的「卡住然后闪退」中，卡住阶段若是主线程阻塞（ANR 前兆），
 * 崩溃报告里只会有结果没有原因；看门狗把阻塞时刻的主线程栈留在日志尾部，
 * 随 CrashReporter 一并落盘。开销：每 4 秒一次主线程 post，可忽略。
 */
object MainThreadWatchdog {

    @Volatile
    private var started = false

    fun start() {
        if (started) return
        started = true
        val main = Handler(Looper.getMainLooper())
        thread(name = "main-thread-watchdog", isDaemon = true) {
            val arrived = AtomicBoolean(false)
            var lastDump = 0L
            while (true) {
                arrived.set(false)
                main.post { arrived.set(true) }
                Thread.sleep(4000)
                if (!arrived.get()) {
                    val now = System.currentTimeMillis()
                    if (now - lastDump >= 10_000) {
                        lastDump = now
                        val stack = Looper.getMainLooper().thread?.stackTrace
                            ?.joinToString("\n") { "    at $it" }
                            .orEmpty()
                        LogStore.log("Watchdog", "E", "主线程阻塞 ≥4s（卡顿/ANR 前兆）。主线程栈：\n$stack")
                    }
                }
            }
        }
    }
}
