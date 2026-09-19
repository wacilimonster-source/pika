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
        loadPersistedHost()?.let {
            baseUrl = it
            lastPersistedHost = it
        }
        // TLS 由 PiKAApp.onCreate 单点安装；此处校验，不可用则重试一次
        // （与 JmClient / UpdateManager 的行为对齐：此前这里只告警不重试）
        if (!BcTls.ensureInstalled()) {
            Log.w("PicaClient", "BcTls unavailable after retry: ${BcTls.lastError}")
            LogStore.log("PicaClient", "E", "BcTls unavailable: ${BcTls.lastError ?: "unknown"}")
        }
        Log.i("PicaClient", "initialized, bcTls=${BcTls.isAvailable()}")
    }

    /**
     * 当前 API 域名。
     * @Volatile 保证跨线程可见性：switchHost 在 IO 协程改它，ensureApi 可能在其他线程读。
     */
    @Volatile
    var baseUrl: String = PicaApiHosts.default
        set(value) {
            // 改域 + 置空 api 必须与 ensureApi 同锁：否则旧协程读到旧 baseUrl 后被挂起，
            // 恢复后会把用旧域名构造的实例写回 _api（换域瞬间个别请求仍打旧域名）
            synchronized(apiLock) {
                field = value
                _api = null
            }
        }

    @Volatile
    private var _api: PicaApi? = null

    /** 与 baseUrl 配套的构造/置空锁，消除 check-then-act 竞态（可能读到过期 baseUrl 重建实例） */
    private val apiLock = Any()

    private fun ensureApi(): PicaApi = synchronized(apiLock) {
        _api ?: PicaHttpApi(baseUrl).also { _api = it }
    }

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

    /**
     * 请求入口（**要求响应带 data**）：适用于查询类接口。
     *
     * @throws PicaException 限流 / 网络失败 / 业务码非 200 / data 缺失
     */
    suspend fun <T> safeCall(block: suspend () -> ApiResponse<T>): T =
        withRetry(block, requireData = true) ?: throw PicaException("空响应数据")

    /**
     * 请求入口（**纯确认型**）：适用于「执行即成功、无返回体」的写操作
     * （发评论 / 改简介 / 改密码 / 改头像 / 改称号 / 忘记密码）。
     *
     * 此前这类接口与查询接口共用同一条「data 必须非空」的校验：一旦服务端返回
     * `{"code":200,"message":"..."}` 而省略 data，就会抛「空响应数据」，
     * 把**已经成功**的操作报成失败（发评论场景会让用户重复提交）。
     */
    suspend fun <T> safeCallUnit(block: suspend () -> ApiResponse<T>) {
        // 只校验业务 code；返回值（可能为 null）由调用方忽略
        withRetry(block, requireData = false)
    }

    /**
     * 统一的请求执行与容错循环：限流冷却 / 换域名重试 / IO 重试。
     * 两个公开入口（[safeCall] / [safeCallUnit]）只差 `requireData` 一个开关，
     * 收敛在此处避免复制那段重试代码。
     */
    private suspend fun <T> withRetry(
        block: suspend () -> ApiResponse<T>,
        requireData: Boolean,
    ): T? {
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
                persistHostIfChanged(baseUrl)
                // 纯确认型：只校验 code，不要求 data（返回 null 由调用方忽略）
                if (!requireData) return null
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
        synchronized(apiLock) {
            baseUrl = if (baseUrl == PicaApiHosts.PICACOMIC) {
                PicaApiHosts.GO2778
            } else {
                PicaApiHosts.PICACOMIC
            }
        }
        Log.i("PicaClient", "switched host -> $baseUrl")
        LogStore.log("PicaClient", "I", "switched host -> $baseUrl")
    }

    /**
     * 成功后记录域名，下次冷启动从它开始，避免反复撞已知不稳的域名。
     *
     * 只在域名**变化**时落盘：此前每次成功请求都写一次 SharedPreferences，
     * 章节 fan-out 等高频路径会把同一份 XML 反复排入落盘队列（纯浪费 IO 与电量）。
     */
    @Volatile
    private var lastPersistedHost: String? = null

    private fun persistHostIfChanged(host: String) {
        if (lastPersistedHost == host) return
        val ctx = appContext ?: return
        runCatching {
            ctx.getSharedPreferences("pika_runtime", Context.MODE_PRIVATE)
                .edit().putString("pica_last_host", host).apply()
            lastPersistedHost = host
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
