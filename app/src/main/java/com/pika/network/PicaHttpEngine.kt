package com.pika.network

import android.util.Log
import com.pika.core.pica.defaultPicaHeaders
import com.pika.core.pica.picaNonce
import com.pika.core.pica.picaSignature
import com.pika.core.pica.picaTimestamp
import com.pika.data.SourcePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 用 HttpsURLConnection 替代 OkHttp 发起哔咔 API 请求。
 *
 * 实测 Cloudflare Bot Management 会拦截 OkHttp（无论底层是 Conscrypt 还是 BCJSSE），
 * 但完全相同的 TLS 栈通过 HttpsURLConnection 就能通过。因此把 Pica 的传输层换成
 * HttpURLConnection，TLS 仍由 BcTls（BCJSSE）提供。
 */
class PicaHttpEngine(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val onUnauthorized: suspend () -> Unit,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** 执行请求（内部为阻塞 IO，统一切到 Dispatchers.IO）。 */
    @Throws(IOException::class)
    suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        bodyJson: String? = null,
    ): RawResponse = withContext(Dispatchers.IO) {
        executeInternal(method, path, query, bodyJson)
    }

    private suspend fun executeInternal(
        method: String,
        path: String,
        query: Map<String, String>,
        bodyJson: String?,
    ): RawResponse {
        val urlBuilder = StringBuilder(baseUrl.removeSuffix("/"))
        urlBuilder.append('/').append(path.removePrefix("/"))
        if (query.isNotEmpty()) {
            urlBuilder.append('?')
            query.entries.joinTo(urlBuilder, "&") { (k, v) ->
                "${encode(k)}=${encode(v)}"
            }
        }

        val url = URL(urlBuilder.toString())
        val conn = url.openConnection() as HttpURLConnection

        // TLS：优先用 BCJSSE；不可用则回退平台默认
        if (conn is HttpsURLConnection) {
            val sf = BcTls.sslSocketFactory
            if (sf != null) {
                conn.sslSocketFactory = sf
            } else {
                BcTls.warnFallbackShared()
            }
        }

        val pathForSign = if (query.isEmpty()) {
            path.removePrefix("/")
        } else {
            "${path.removePrefix("/")}?${buildQueryString(query)}"
        }
        val nonce = picaNonce()
        val timestamp = picaTimestamp()
        val signature = picaSignature(pathForSign, timestamp, nonce, method)
        val appUuid = SourcePrefs.current().getOrCreateAppUuidAsync()
        val token = tokenProvider()

        conn.requestMethod = method
        conn.doInput = true
        conn.doOutput = bodyJson != null
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        // 保留自动跟随重定向（关闭可能在宿主域名正常跳转时让全部请求失败，属未经验证的行为变更）。
        // 改为在下方检测"最终 URL 与请求 URL 不一致"并记录告警：这类跳转若是被网关导向登录页，
        // 会以 200 + HTML 返回，使 401 检测被绕过，用户看到的是"数据解析失败"而非"请重新登录"。
        conn.instanceFollowRedirects = true

        defaultPicaHeaders(appUuid).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("time", timestamp)
        conn.setRequestProperty("nonce", nonce)
        conn.setRequestProperty("signature", signature)
        conn.setRequestProperty("image-quality", "original")
        token?.let { conn.setRequestProperty("authorization", it) }

        if (bodyJson != null) {
            // 不手工设置 Content-Length：HttpsURLConnection 会按实际写入的字节数自行计算，
            // 手工声明既无效（会被覆盖），又容易与实际长度不符。
            val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bodyBytes) }
        }

        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }

        // 3xx（未跟随的重定向）：多半是被网关导向登录页，按会话失效处理
        if (code in 300..399) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            throw PicaException(
                "请求被重定向($code)${if (location.isNullOrBlank()) "" else " 至 $location"}，疑似会话失效，请重新登录",
                httpCode = code,
            )
        }

        // 已跟随的重定向诊断：最终 URL 与请求 URL 不一致说明发生过跳转。
        // 这类跳转若指向登录页，会以 200 + HTML 返回，使 401 检测被绕过，
        // 上层最终报"数据解析失败"。这里留下可检索的痕迹，便于区分"解析失败"的真实原因。
        run {
            val finalUrl = conn.url?.toString()
            if (finalUrl != null && finalUrl != urlBuilder.toString()) {
                com.pika.core.log.LogStore.log(
                    "PicaHttpEngine", "W",
                    "redirected: $urlBuilder -> $finalUrl (可能掩盖 401/会话失效)",
                )
            }
        }

        if (code == 401) {
            onUnauthorized()
            conn.disconnect()
            throw PicaException("登录已过期(401)，请重新登录")
        }

        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bodyBytes = try {
            stream?.use { it.readBytes() }
                ?: ByteArray(0)
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
        // errorStream 为 null（如 5xx / 连接被重置）时补充状态行，避免上层错误信息为空
        val finalBody = if (bodyBytes.isEmpty() && code !in 200..299) {
            ("HTTP $code ${conn.responseMessage ?: "Unknown"}").toByteArray(Charsets.UTF_8)
        } else {
            bodyBytes
        }

        return RawResponse(
            code = code,
            body = finalBody,
            connection = conn,
        )
    }

    private fun buildQueryString(query: Map<String, String>): String =
        query.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    /** RFC 3986 percent-encoding（空格 → %20，而非表单的 +），保证 URL 与签名原文一致 */
    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    data class RawResponse(
        val code: Int,
        val body: ByteArray,
        private val connection: HttpURLConnection,
    ) {
        val bodyString: String get() = body.toString(Charsets.UTF_8)
        fun close() = connection.disconnect()
    }
}
