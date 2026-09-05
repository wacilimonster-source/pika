package com.pika.network

import com.pika.core.model.ComicSort
import com.pika.data.SourcePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class JmException(message: String) : IOException(message)

/**
 * 禁漫移动端 API 客户端（实测修复版，2026-09-03）。
 *
 * 关键修复（相对旧版）：
 *  - 默认域名改为仍然可用的镜像（旧 api.jmcomic1.com 已失效）
 *  - 路径去掉已废弃的 /api/v3 前缀
 *  - 每个请求带签名头 token/tokenparam
 *  - 响应 data 解密（AES-256-ECB）后再交给 kotlinx 解析
 *  - 登录后通过 Cookie: AVS={s} 携带会话
 */
object JmClient {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val client: OkHttpClient by lazy {
        BcTls.install()
        BcTls.applyTo(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
        ).build()
    }

    /** 仍然可用的 API 镜像域名（设置页可配置覆盖） */
    const val DEFAULT_BASE = "https://www.cdngwc.cc"

    val baseUrl: String
        get() = SourcePrefs.current().jmBaseUrl ?: DEFAULT_BASE

    /** 会话失效钩子（由 SourceManager 注入，避免网络层反向依赖上层）：401 时静默重登 */
    var onUnauthorizedHook: (suspend () -> Unit)? = null

    private val headers: Map<String, String> get() = buildMap {
        put("device", "ANDROID;9.0;SMR;unknown;deadbeef12345678;2.1.3")
        put("os-version", "9.0")
        put("platform", "ANDROID")
        put("app-version", "3.4.17")
        put("channel", "app")
        put("User-Agent", "okhttp/3.12.0 leak(200.0);Android version:9.0;MAX2;100;jmc;3.23.0")
    }

    /** 统一请求：带签名头 + 解密响应；会话过期(401)时触发钩子重登并重试一次 */
    private suspend fun execute(
        relative: String,
        form: Map<String, String>? = null,
        retriedAuth: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val sign = JmCrypto.sign()
        val builder = Request.Builder()
            .url(baseUrl.trimEnd('/') + relative)
            .header("token", sign.token)
            .header("tokenparam", sign.tokenparam)
        headers.forEach { (k, v) -> builder.header(k, v) }
        // 登录态：会话放入 Cookie（仅收藏/签到/历史等需登录端点真正依赖）
        val session = SourcePrefs.current().jmToken
        if (!session.isNullOrEmpty()) builder.header("Cookie", "AVS=$session")

        if (form != null) {
            val body = form.map { (k, v) -> "${k}=${URLEncoder.encode(v, "UTF-8")}" }.joinToString("&")
            builder.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        } else {
            builder.get()
        }

        client.newCall(builder.build()).execute().use { resp ->
            if (resp.code == 401 && !retriedAuth) {
                onUnauthorizedHook?.invoke()
                return@withContext execute(relative, form, retriedAuth = true)
            }
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw JmException("禁漫接口 ${resp.code}: ${text.take(200)}")
            decryptEnvelope(text, sign.ts)
        }
    }

    /** 把加密的 data 字段解密后替换回原 JSON，再返回完整可解析文本 */
    private fun decryptEnvelope(text: String, ts: Long): String {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return text
        val dataEl = root["data"]
        if (dataEl !is JsonPrimitive || !dataEl.isString) return text
        val plain = runCatching { JmCrypto.decrypt(dataEl.content, ts) }
            .getOrElse { throw JmException("禁漫响应解密失败（密钥/时间戳可能漂移）：${it.message}") }
        val newData = runCatching { Json.parseToJsonElement(plain) }
            .getOrElse { throw JmException("禁漫解密结果非法：${it.message}") }
        val newRoot = buildJsonObject {
            root.forEach { (k, v) -> put(k, v) }
            put("data", newData)
        }
        return json.encodeToString(JsonObject.serializer(), newRoot)
    }

    // ---------- API ----------

    suspend fun categories(): JmCategoriesResponse =
        json.decodeFromString(execute("/categories"))

    /** 浏览 / 分类流：/categories/filter（DA 为客户端倒序实现） */
    suspend fun browse(page: Int, category: String?, sort: ComicSort): JmListResponse {
        val q = buildString {
            append("/categories/filter?page=").append(page)
            append("&t=a")
            if (!category.isNullOrBlank()) append("&c=").append(URLEncoder.encode(category, "UTF-8"))
        }
        return fetchList(q, sort)
    }

    /** 排行榜：/categories/filter 配 o=mv_t/mv_w/mv_m */
    suspend fun rankList(order: String, page: Int = 1): JmListResponse {
        val q = "/categories/filter?page=$page&o=$order&t=a"
        return json.decodeFromString(execute(q))
    }

    /** 搜索：/search（main_tag=0 综合维度） */
    suspend fun search(keyword: String, page: Int, sort: ComicSort): JmListResponse {
        val q = "/search?search_query=${URLEncoder.encode(keyword, "UTF-8")}&page=$page&main_tag=0&t=a"
        return fetchList(q, sort)
    }

    /** 详情：/album?id= （扁平结构，series 即章节列表） */
    suspend fun album(albumId: String): JmAlbumResponse =
        json.decodeFromString(execute("/album?id=${URLEncoder.encode(albumId, "UTF-8")}"))

    /** 章节图片：/chapter?id={photoId} 一次返回整章 images 列表 */
    suspend fun chapter(photoId: String): JmChapterResponse =
        json.decodeFromString(execute("/chapter?id=${URLEncoder.encode(photoId, "UTF-8")}"))

    /** 评论：/forum?mode=all&page=&aid= */
    suspend fun comments(albumId: String, page: Int): JmForumResponse =
        json.decodeFromString(execute("/forum?mode=all&page=$page&aid=${URLEncoder.encode(albumId, "UTF-8")}"))

    /** 每周必看：/week */
    suspend fun week(): JmWeekResponse =
        json.decodeFromString(execute("/week"))

    /** 登录：POST /login（form: username + password）；返回会话密钥 s */
    suspend fun login(email: String, password: String): String {
        val text = execute("/login", mapOf("username" to email, "password" to password))
        val resp = json.decodeFromString(JmLoginResponse.serializer(), text)
        if (resp.data.s.isBlank()) throw JmException("禁漫登录失败：${resp.errorMsg ?: text.take(120)}")
        return resp.data.s
    }

    /** 退出登录：GET /logout（尽力而为，失败不抛） */
    suspend fun logout() {
        runCatching { execute("/logout") }
    }

    // ---------- 需登录：收藏 / 签到 / 历史 ----------

    /** 收藏列表：GET /favorite?page=&folder_id=0&o=mr（移动端无 folder_id） */
    suspend fun favorites(page: Int): JmListResponse =
        json.decodeFromString(execute("/favorite?page=$page&folder_id=0&o=mr"))

    /**
     * 收藏 / 取消收藏切换。
     * 社区库实测：添加为 GET /favorite?aid={id}。取消字段官方移动端未公开，
     * 暂用 type 参数（1=收藏, 0=取消），待真实账号验证后微调。
     */
    suspend fun favoriteToggle(aid: String, add: Boolean): Boolean {
        val type = if (add) "1" else "0"
        val text = execute("/favorite?aid=${URLEncoder.encode(aid, "UTF-8")}&type=$type")
        return json.decodeFromString(JmActionResponse.serializer(), text).code == 200
    }

    /** 签到状态：GET /daily */
    suspend fun dailyStatus(): JmDailyResponse =
        json.decodeFromString(execute("/daily"))

    /** 执行签到：GET /daily_chk */
    suspend fun dailyCheckIn(): JmDailyResponse =
        json.decodeFromString(execute("/daily_chk"))

    /** 云端浏览历史：GET /watch_list?page= */
    suspend fun watchList(page: Int): JmListResponse =
        json.decodeFromString(execute("/watch_list?page=$page"))

    /**
     * 列表请求统一入口：附加排序参数；服务端无升序参数（DA），客户端倒序实现。
     * 注意：倒序只对当前页生效，跨页语义为"按最新在前分页的逆序"。
     */
    private suspend fun fetchList(q: String, sort: ComicSort): JmListResponse {
        val full = "$q&o=${sortToO(sort)}"
        val resp = json.decodeFromString<JmListResponse>(execute(full))
        return if (sort == ComicSort.DA) {
            resp.copy(data = resp.data.copy(content = resp.data.content.asReversed()))
        } else {
            resp
        }
    }

    private fun sortToO(sort: ComicSort): String = when (sort) {
        ComicSort.DD -> "mr"   // 最新
        ComicSort.DA -> "mr"   // 服务端无升序参数，fetchList 中客户端倒序
        ComicSort.LD -> "tf"   // 最多喜欢
        ComicSort.VD -> "mv"   // 最多观看
    }
}
