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
import java.net.Proxy
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

    /** 同步执行请求，返回原始响应字节。 */
    @Throws(IOException::class)
    suspend fun execute(
        method: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        bodyJson: String? = null,
    ): RawResponse = executeInternal(method, path, query, bodyJson)

    /** 协程包装。 */
    suspend fun executeAsync(
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
                Log.w("PicaHttpEngine", "BCJSSE 不可用，回退平台 TLS")
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
        val appUuid = SourcePrefs.current().getOrCreateAppUuid()
        val token = tokenProvider()

        conn.requestMethod = method
        conn.doInput = true
        conn.doOutput = bodyJson != null
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        conn.instanceFollowRedirects = true

        defaultPicaHeaders(appUuid).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("time", timestamp)
        conn.setRequestProperty("nonce", nonce)
        conn.setRequestProperty("signature", signature)
        conn.setRequestProperty("image-quality", "original")
        token?.let { conn.setRequestProperty("authorization", it) }

        if (bodyJson != null) {
            val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
            conn.setRequestProperty("Content-Length", bodyBytes.size.toString())
            conn.outputStream.use { it.write(bodyBytes) }
        }

        val code = try {
            conn.responseCode
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }

        if (code == 401) {
            onUnauthorized()
        }

        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val bodyBytes = try {
            stream?.use { it.readBytes() } ?: ByteArray(0)
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }

        return RawResponse(
            code = code,
            body = bodyBytes,
            headers = conn.headerFields
                .filter { it.key != null }
                .flatMap { (k, vs) -> vs.map { k to it } },
            connection = conn,
        )
    }

    private fun buildQueryString(query: Map<String, String>): String =
        query.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    data class RawResponse(
        val code: Int,
        val body: ByteArray,
        val headers: List<Pair<String, String>>,
        private val connection: HttpURLConnection,
    ) {
        val bodyString: String get() = body.toString(Charsets.UTF_8)
        fun close() = connection.disconnect()
    }
}
