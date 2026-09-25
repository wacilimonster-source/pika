package com.pika.util

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.pika.network.BcTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 图片保存到系统相册（Pictures/PiKA）。
 *
 * Android 10+（本应用 minSdk 26 需分流程）：API 29+ 走 MediaStore 无需存储权限；
 * API 26-28 需要 WRITE_EXTERNAL_STORAGE（阅读器长按保存是低频动作，
 * 未授权时直接报错提示，不主动申请权限打断阅读）。
 */
object ImageSaver {

    /** 保存图片到相册，返回显示名。[source] 支持 http(s) URL 与 file: URI（离线已下载页） */
    suspend fun saveToGallery(context: Context, source: String, comicTitle: String, page: Int): String =
        withContext(Dispatchers.IO) {
            val safeTitle = comicTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(50)
            val displayName = "${safeTitle}_第${page}页_${System.currentTimeMillis()}.jpg"

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PiKA")
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法写入系统相册")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    openSource(source).use { input ->
                        input.copyTo(out, 64 * 1024)
                    }
                } ?: throw IOException("无法打开输出流")
            } catch (e: Exception) {
                // 失败清理半成品记录，避免相册里留下 0 字节坏图
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }
            displayName
        }

    private fun openSource(source: String): java.io.InputStream =
        if (source.startsWith("file:")) {
            File(java.net.URI(source)).inputStream()
        } else {
            val url = URL(source)
            val conn: HttpURLConnection = if (url.protocol == "https") {
                BcTls.openConnection(url)
            } else {
                url.openConnection() as HttpURLConnection
            }
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "okhttp/4.12.0")
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("HTTP $code")
            }
            // 包装断开连接：流读完自动 disconnect，避免泄漏
            object : java.io.InputStream() {
                private val delegate = conn.inputStream
                override fun read(): Int = delegate.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
                override fun close() {
                    runCatching { delegate.close() }
                    conn.disconnect()
                }
            }
        }
}
