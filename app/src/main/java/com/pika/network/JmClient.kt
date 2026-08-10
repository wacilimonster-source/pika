package com.pika.network

import com.pika.data.SourcePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class JmException(message: String) : IOException(message)

/**
 * 禁漫移动端 v3 API 客户端。
 * 官方 API 需要登录 token；域名为镜像制（设置页可配置 baseUrl）。
 */
object JmClient {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    const val DEFAULT_BASE = "https://api.jmcomic1.com"

    /** 域名镜像可配置：来源 SourcePrefs，默认 DEFAULT_BASE */
    val baseUrl: String
        get() = SourcePrefs.current().jmBaseUrl ?: DEFAULT_BASE

    private val headers: Map<String, String> get() = buildMap {
        val token = SourcePrefs.current().jmToken
        if (!token.isNullOrEmpty()) put("token", token)
        put("device", "ANDROID;9.0;SMR;unknown;deadbeef12345678;2.1.3")
        put("os-version", "9.0")
        put("platform", "ANDROID")
        put("app-version", "3.4.17")
        put("channel", "app")
        put("User-Agent", "okhttp/3.12.0 leak(200.0);Android version:9.0;MAX2;100;jmc;3.23.0")
        put("Content-Type", "application/json")
    }

    private suspend fun execute(relative: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + relative
            val builder = Request.Builder().url(url)
            headers.forEach { (k, v) -> builder.header(k, v) }
            if (body != null) {
                builder.post(body.toRequestBody("application/json".toMediaType()))
            } else {
                builder.get()
            }
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw JmException("禁漫接口 ${response.code}: ${text.take(200)}")
                }
                text
            }
        }

    // ---------- API ----------

    suspend fun login(email: String, password: String): String {
        val body = json.encodeToString(JmLoginRequestBody.serializer(), JmLoginRequestBody(email, password))
        val text = execute("/api/v3/auth/sign-in", body)
        val resp = json.decodeFromString(JmLoginResponse.serializer(), text)
        if (resp.token.isBlank()) throw JmException("禁漫登录失败：${text.take(120)}")
        return resp.token
    }

    suspend fun categories(): List<JmCategory> {
        val text = execute("/api/v3/categories")
        return json.decodeFromString(JmCategoriesResponse.serializer(), text).categories
    }

    suspend fun commendList(page: Int, category: String? = null): JmAlbumListResponse {
        val q = buildString {
            append("/api/v3/commend_list_page?page=").append(page)
            if (!category.isNullOrBlank()) append("&c=").append(category)
        }
        val text = execute(q)
        return json.decodeFromString(JmAlbumListResponse.serializer(), text)
    }

    suspend fun search(keyword: String, page: Int): JmSearchResponse {
        val q = "/api/v3/search?page=$page&search_query=${URLEncoder.encode(keyword, "UTF-8")}"
        val text = execute(q)
        return json.decodeFromString(JmSearchResponse.serializer(), text)
    }

    suspend fun album(albumId: String): JmAlbumResponse {
        val text = execute("/api/v3/album/$albumId?with_media=true")
        return json.decodeFromString(JmAlbumResponse.serializer(), text)
    }

    suspend fun albumPage(albumId: String, photoIndex: Int, pageIndex: Int): JmAlbumPageResponse {
        val text = execute("/api/v3/album_page/$albumId/$photoIndex/$pageIndex")
        return json.decodeFromString(JmAlbumPageResponse.serializer(), text)
    }
}

@Serializable
private data class JmLoginRequestBody(
    val email: String,
    val password: String,
)