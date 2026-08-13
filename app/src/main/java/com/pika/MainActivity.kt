package com.pika

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pika.ui.MainScreen
import com.pika.ui.theme.PiKATextTheme
import com.pika.ui.theme.PiKATheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        enableEdgeToEdge()
        setContent {
            PiKATheme {
                PiKATextTheme {
                    MainScreen()
                }
            }
        }
    }

    /** 全局未捕获异常：写 crash.log（保留最近 5 次），便于离线定位闪退 */
    private fun installCrashLogger() {
        val dir = File(filesDir, "crash").apply { mkdirs() }
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("MM-dd_HH-mm-ss", Locale.US).format(Date())
                val file = File(dir, "crash_$ts.log")
                file.writeText(throwable.stackTraceToString())
                dir.listFiles()?.sortedByDescending { it.lastModified() }
                    ?.drop(5)
                    ?.forEach { it.delete() }
            } catch (e: Exception) {
                Log.e("CrashLogger", "write crash log failed", e)
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}