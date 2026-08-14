package com.pika.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.pika.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.CacheControl
import com.pika.network.BcTls
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 应用更新：从 GitHub 仓库的 update.json 检查版本，下载 APK 并安装。
 *
 * update.json 结构：{ "version": "0.3.0", "apkUrl": "https://raw.githubusercontent.com/.../pika-v0.3.0.apk", "notes": "更新说明" }
 */
object UpdateManager {

    /** 与仓库 update.json 保持一致 */
    const val UPDATE_URL =
        "https://raw.githubusercontent.com/wacilimonster-source/pika/main/update.json"

    @Serializable
    data class UpdateInfo(
        val version: String = "",
        val apkUrl: String = "",
        val notes: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient by lazy {
        BcTls.install()
        BcTls.applyTo(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
        ).build()
    }

    val currentVersionName: String
        get() = BuildConfig.VERSION_NAME

    /**
     * 检查更新：拉取远端信息并对比版本号。
     * 返回 null 表示已是最新或检查失败。
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val info = runCatching {
            val urlWithTs = "$UPDATE_URL?ts=${System.currentTimeMillis()}"
            val request = Request.Builder()
                .url(urlWithTs)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .build()
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@runCatching null
                json.decodeFromString(UpdateInfo.serializer(), text)
            }
        }.getOrNull() ?: return@withContext null

        if (info.version.isBlank() || info.apkUrl.isBlank()) return@withContext null
        if (!isNewer(info.version, currentVersionName)) return@withContext null
        info
    }

    /**
     * 下载 APK 到 cache 目录，带进度回调（0f..1f）。
     */
    suspend fun download(context: Context, url: String, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw java.io.IOException("下载失败：HTTP ${resp.code}")
                }
                val body = resp.body ?: throw java.io.IOException("下载失败：空响应")
                val total = body.contentLength()
                val target = File(context.cacheDir, "pika-update.apk")
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read: Int
                        var done = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                onProgress(done.toFloat() / total.toFloat())
                            }
                        }
                    }
                }
                target
            }
        }

    /** 拉起系统安装器 */
    fun install(context: Context, apk: File): Boolean {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 语义化版本比较：latest > current 才算有新版本 */
    fun isNewer(latest: String, current: String): Boolean {
        val parse: (String) -> List<Int> = { raw ->
            raw.trim().trimStart('v').split('.').mapNotNull { it.toIntOrNull() }
        }
        val a = parse(latest)
        val b = parse(current)
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}