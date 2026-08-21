package com.pika.network

import android.content.Context
import android.util.Log
import com.pika.core.log.LogStore
import com.pika.core.source.SourceManager
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 哔咔 API 入口：现在由 HttpsURLConnection + BCJSSE 驱动。
 *
 * Cloudflare 的 Bot Management 会拦截 OkHttp（无论 TLS 提供者是 Conscrypt 还是 BCJSSE），
 * 但完全相同的 TLS 栈通过 HttpsURLConnection 即可返回 200。因此把传输层从 OkHttp/Retrofit
 * 切换到 HttpURLConnection，保留签名、重试、域名切换等上层逻辑。
 */
object PicaClient {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        BcTls.install()
        Log.i("PicaClient", "initialized, bcTls=${BcTls.isAvailable()}")
    }

    var baseUrl: String = PicaApiHosts.default
        set(value) {
            field = value
            _api = null
        }

    private var _api: PicaApi? = null

    val api: PicaApi
        get() = _api ?: PicaHttpApi(baseUrl).also { _api = it }

    private const val RATE_LIMIT_COOLDOWN_MS = 60_000L

    @Volatile
    private var rateLimitedUntil: Long = 0L

    fun rateLimitRemaining(): Long =
        (rateLimitedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    suspend fun <T> safeCall(block: suspend () -> ApiResponse<T>): T {
        var attempt = 0
        while (true) {
            val cooldown = rateLimitRemaining()
            if (cooldown > 0) {
                throw PicaException("请求过于频繁(429)，请 ${(cooldown + 999) / 1000} 秒后再试")
            }
            try {
                val response = block()
                if (response.code != 200) {
                    LogStore.log("PicaClient", "E", "HTTP ${response.code}: ${response.message}")
                    throw PicaException(response.message)
                }
                return response.data ?: throw PicaException("空响应数据")
            } catch (e: PicaException) {
                val message = e.message.orEmpty()
                val isRateLimit = message.contains("too many requests", ignoreCase = true)
                        || message.contains("1023")
                if (isRateLimit && attempt == 0) {
                    attempt++
                    Log.i("PicaClient", "rate limited, switching host and retry")
                    LogStore.log("PicaClient", "W", "rate limited, switching host and retry")
                    delay(2_000)
                    switchHost()
                    continue
                }
                if (isRateLimit) {
                    rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                    LogStore.log("PicaClient", "E", "rate limited exceeded, cooldown ${RATE_LIMIT_COOLDOWN_MS / 1000}s")
                    throw PicaException("请求过于频繁(429)，请 ${RATE_LIMIT_COOLDOWN_MS / 1000} 秒后再试")
                }
                throw e
            } catch (e: java.io.IOException) {
                attempt++
                if (attempt <= 2) {
                    Log.i("PicaClient", "network error, retry $attempt: ${e.message}")
                    LogStore.log("PicaClient", "W", "network error, retry $attempt: ${e.message}")
                    delay(1_000L * attempt)
                    switchHost()
                    continue
                }
                LogStore.log("PicaClient", "E", "network failed after $attempt attempts: ${e.message}")
                throw PicaException("网络连接失败：${e.message}")
            }
        }
    }

    private fun switchHost() {
        baseUrl = if (baseUrl == PicaApiHosts.PICACOMIC) {
            PicaApiHosts.GO2778
        } else {
            PicaApiHosts.PICACOMIC
        }
        Log.i("PicaClient", "switched host -> $baseUrl")
        LogStore.log("PicaClient", "I", "switched host -> $baseUrl")
    }
}
