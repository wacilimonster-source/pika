package com.pika.network

import com.pika.core.source.SourceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

/**
 * PicaApi 的 HttpsURLConnection 实现。
 *
 * 保留原 Retrofit 接口的签名，但底层不再走 OkHttp，从而绕过 Cloudflare
 * 对 OkHttp ClientHello/HTTP 指纹的拦截。
 */
class PicaHttpApi(baseUrl: String) : PicaApi {

    private val engine = PicaHttpEngine(
        baseUrl = baseUrl,
        tokenProvider = { SourceManager.picaToken() },
        onUnauthorized = { SourceManager.onUnauthorized() },
    )

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private inline fun <reified T> parseResponse(resp: PicaHttpEngine.RawResponse): ApiResponse<T> {
        try {
            return json.decodeFromString(
                ApiResponse.serializer(serializer()),
                resp.bodyString
            )
        } finally {
            resp.close()
        }
    }

    private suspend inline fun <reified T> get(
        path: String,
        query: Map<String, String> = emptyMap(),
    ): ApiResponse<T> {
        val resp = engine.executeAsync("GET", path, query)
        if (resp.code !in 200..299) throw PicaException("${resp.code}: ${resp.bodyString}")
        return parseResponse(resp)
    }

    private suspend inline fun <reified T, reified B> post(
        path: String,
        body: B,
        query: Map<String, String> = emptyMap(),
    ): ApiResponse<T> {
        val bodyJson = json.encodeToString(serializer<B>(), body)
        val resp = engine.executeAsync("POST", path, query, bodyJson)
        if (resp.code !in 200..299) throw PicaException("${resp.code}: ${resp.bodyString}")
        return parseResponse(resp)
    }

    private suspend inline fun <reified T> postEmpty(
        path: String,
    ): ApiResponse<T> {
        val resp = engine.executeAsync("POST", path, emptyMap(), "{}")
        if (resp.code !in 200..299) throw PicaException("${resp.code}: ${resp.bodyString}")
        return parseResponse(resp)
    }

    override suspend fun login(body: LoginPayload): ApiResponse<LoginResponse> =
        post("auth/sign-in", body)

    override suspend fun categories(): ApiResponse<CategoriesResponse> =
        get("categories")

    override suspend fun comics(params: Map<String, String>): ApiResponse<ComicsResponse> =
        get("comics", params)

    override suspend fun comic(id: String): ApiResponse<ComicDetailsResponse> =
        get("comics/$id")

    override suspend fun chapters(id: String, page: Int): ApiResponse<ChaptersResponse> =
        get("comics/$id/eps", mapOf("page" to page.toString()))

    override suspend fun chapterImages(
        id: String,
        order: Int,
        page: Int,
    ): ApiResponse<FetchChapterImagesResponse> =
        get("comics/$id/order/$order/pages", mapOf("page" to page.toString()))

    override suspend fun search(page: Int, body: SearchPayload): ApiResponse<SearchResponse> =
        post("comics/advanced-search", body, mapOf("page" to page.toString()))

    override suspend fun profile(): ApiResponse<UserProfileResponse> =
        get("users/profile")

    override suspend fun favourites(params: Map<String, String>): ApiResponse<ComicsResponse> =
        get("users/favourite", params)

    override suspend fun leaderboard(params: Map<String, String>): ApiResponse<ComicRankResponse> =
        get("comics/leaderboard", params)

    override suspend fun hotSearch(): ApiResponse<HotSearchWordsResponse> =
        get("keywords")

    override suspend fun random(): ApiResponse<RandomComicsResponse> =
        get("comics/random")

    override suspend fun punchIn(): ApiResponse<JsonObject> =
        postEmpty("users/punch-in")

    override suspend fun favorite(id: String): ApiResponse<ActionResponse> =
        postEmpty("comics/$id/favourite")

    override suspend fun recommendation(id: String): ApiResponse<RecommendComics> =
        get("comics/$id/recommendation")
}
