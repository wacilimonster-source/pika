package com.pika.network

import android.content.Context
import android.util.Log
import com.pika.core.log.LogStore
import com.pika.core.source.SourceManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        loadPersistedHost()?.let { baseUrl = it }
        // TLS 由 PiKAApp.onCreate 单点安装；此处仅校验并记录，不再重复 install
        if (!BcTls.isAvailable()) {
            Log.w("PicaClient", "BcTls not installed yet (expect PiKAApp to install it)")
            LogStore.log("PicaClient", "W", "BcTls not installed at PicaClient.init")
        }
        Log.i("PicaClient", "initialized, bcTls=${BcTls.isAvailable()}")
    }

    var baseUrl: String = PicaApiHosts.default
        set(value) {
            field = value
            _api = null
        }

    @Volatile
    private var _api: PicaApi? = null

    // PicaHttpApi 构造成本极低（仅创建 engine 对象），这里用 @Synchronized 兜住
    // check-then-act 竞态；并发首次访问最多多构造一个等价实例，无功能影响。
    @Synchronized
    private fun ensureApi(): PicaApi =
        _api ?: PicaHttpApi(baseUrl).also { _api = it }

    val api: PicaApi
        get() = _api ?: ensureApi()

    private const val RATE_LIMIT_COOLDOWN_MS = 20_000L

    @Volatile
    private var rateLimitedUntil: Long = 0L

    fun rateLimitRemaining(): Long =
        (rateLimitedUntil - System.currentTimeMillis()).coerceAtLeast(0L)

    /**
     * 域名切换串行化 + 3 秒去抖：并发请求同时失败时（如章节列表并发拉取），
     * 避免多协程各自 switchHost 来回打架（A 切到 B、B 又切回 A）。
     */
    private val hostSwitchMutex = Mutex()
    @Volatile
    private var lastHostSwitchAt = 0L

    private suspend fun switchHostThrottled() {
        hostSwitchMutex.withLock {
            val now = System.currentTimeMillis()
            if (now - lastHostSwitchAt < 3_000) return
            lastHostSwitchAt = now
            switchHost()
        }
    }

    suspend fun <T> safeCall(block: suspend () -> ApiResponse<T>): T {
        var rateLimitAttempt = 0
        var ioAttempt = 0
        while (true) {
            val cooldown = rateLimitRemaining()
            if (cooldown > 0) {
                throw PicaException("请求过于频繁(429)，请 ${(cooldown + 999) / 1000} 秒后再试", httpCode = 429)
            }
            try {
                val response = block()
                if (response.code != 200) {
                    LogStore.log("PicaClient", "E", "HTTP ${response.code}: ${response.message}")
                    throw PicaException(response.message, errorCode = response.code)
                }
                persistHost(baseUrl)
                return response.data ?: throw PicaException("空响应数据")
            } catch (e: PicaException) {
                // 结构化判定：HTTP 429 或业务码 1023；文案匹配仅作兜底（旧服务端可能不带 code）
                val isRateLimit = e.isRateLimit
                        || e.message.orEmpty().contains("too many requests", ignoreCase = true)
                if (isRateLimit && rateLimitAttempt == 0) {
                    rateLimitAttempt++
                    Log.i("PicaClient", "rate limited, switching host and retry")
                    LogStore.log("PicaClient", "W", "rate limited, switching host and retry")
                    delay(2_000)
                    switchHostThrottled()
                    continue
                }
                if (isRateLimit) {
                    rateLimitedUntil = System.currentTimeMillis() + RATE_LIMIT_COOLDOWN_MS
                    LogStore.log("PicaClient", "E", "rate limited exceeded, cooldown ${RATE_LIMIT_COOLDOWN_MS / 1000}s")
                    throw PicaException(
                        "请求过于频繁(429)，请 ${RATE_LIMIT_COOLDOWN_MS / 1000} 秒后再试",
                        httpCode = 429,
                    )
                }
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                ioAttempt++
                if (ioAttempt <= 2) {
                    Log.i("PicaClient", "network error, retry $ioAttempt: ${e.message}")
                    LogStore.log("PicaClient", "W", "network error, retry $ioAttempt: ${e.message}")
                    delay(1_000L * ioAttempt)
                    switchHostThrottled()
                    continue
                }
                LogStore.log("PicaClient", "E", "network failed after $ioAttempt attempts: ${e.message}")
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

    /** 成功后记录域名，下次冷启动从它开始，避免反复撞已知不稳的域名 */
    private fun persistHost(host: String) {
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences("pika_runtime", Context.MODE_PRIVATE)
                .edit().putString("pica_last_host", host).apply()
        }
    }

    private fun loadPersistedHost(): String? {
        val ctx = appContext ?: return null
        return runCatching {
            ctx.getSharedPreferences("pika_runtime", Context.MODE_PRIVATE)
                .getString("pica_last_host", null)
        }.getOrNull()?.takeIf { it == PicaApiHosts.PICACOMIC || it == PicaApiHosts.GO2778 }
    }
}
