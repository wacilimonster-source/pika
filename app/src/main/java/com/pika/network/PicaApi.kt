package com.pika.network

import com.pika.core.pica.ComicRankType
import com.pika.core.pica.ComicSortType
import com.pika.core.pica.PicaApi
import kotlinx.serialization.json.JsonObject

/**
 * 哔咔 API 方法契约。
 * 传输层已由 PicaHttpApi（HttpsURLConnection）实现；路径信息见 PicaHttpApi 内部映射，
 * 此接口仅作为签名契约，不再使用 Retrofit 注解（Retrofit 已从依赖中移除）。
 */
interface PicaApi {
    suspend fun login(body: LoginPayload): ApiResponse<LoginResponse>

    suspend fun register(body: RegisterPayload): ApiResponse<JsonObject>

    suspend fun categories(): ApiResponse<CategoriesResponse>

    suspend fun comics(params: Map<String, String>): ApiResponse<ComicsResponse>

    suspend fun comic(id: String): ApiResponse<ComicDetailsResponse>

    suspend fun chapters(id: String, page: Int): ApiResponse<ChaptersResponse>

    suspend fun chapterImages(id: String, order: Int, page: Int): ApiResponse<FetchChapterImagesResponse>

    suspend fun search(page: Int, body: SearchPayload): ApiResponse<SearchResponse>

    suspend fun profile(): ApiResponse<UserProfileResponse>

    suspend fun favourites(params: Map<String, String>): ApiResponse<ComicsResponse>

    suspend fun leaderboard(params: Map<String, String>): ApiResponse<ComicRankResponse>

    suspend fun hotSearch(): ApiResponse<HotSearchWordsResponse>

    suspend fun random(): ApiResponse<RandomComicsResponse>

    suspend fun punchIn(): ApiResponse<JsonObject>

    suspend fun favorite(id: String): ApiResponse<ActionResponse>

    suspend fun recommendation(id: String): ApiResponse<RecommendComics>

    // ---------- 评论 ----------

    suspend fun comments(id: String, page: Int): ApiResponse<CommentsResponse>

    suspend fun sendComment(id: String, body: SendCommentPayload): ApiResponse<Comment>

    suspend fun replyComment(id: String, body: SendCommentPayload): ApiResponse<Comment>

    suspend fun commentChildren(id: String, page: Int): ApiResponse<CommentsResponse>

    suspend fun myComments(page: Int): ApiResponse<PersonalCommentsResponse>

    // ---------- 账号 ----------

    suspend fun forgotPassword(body: ForgotPasswordPayload): ApiResponse<JsonObject>

    suspend fun updateProfile(body: UpdateProfilePayload): ApiResponse<JsonObject>

    suspend fun updatePassword(body: UpdatePasswordPayload): ApiResponse<JsonObject>

    suspend fun updateAvatar(body: UpdateAvatarPayload): ApiResponse<JsonObject>

    suspend fun updateTitle(id: String, body: UpdateTitlePayload): ApiResponse<JsonObject>
}

/** 排序/排行查询参数构造 */
fun comicsQuery(
    page: Int = 1,
    category: String? = null,
    sort: ComicSortType? = null,
    tag: String? = null,
    author: String? = null,
    chineseTeam: String? = null,
    uploader: String? = null,
): Map<String, String> = buildMap {
    put("page", page.toString())
    if (category != null) put("c", category)
    if (sort != null) put("s", sort.name.lowercase())
    if (tag != null) put("t", tag)
    if (author != null) put("a", author)
    if (chineseTeam != null) put("ct", chineseTeam)
    if (uploader != null) put("ca", uploader)
}

fun rankQuery(type: ComicRankType): Map<String, String> = mapOf(
    // 服务端要求大写枚举名：H24 / D7 / D30
    "tt" to type.name,
    "ct" to "VC",
)

fun favouriteQuery(page: Int, sort: ComicSortType): Map<String, String> = mapOf(
    "page" to page.toString(),
    "s" to sort.name.lowercase(),
)

/** 指定 API 域名常量 */
object PicaApiHosts {
    const val PICACOMIC = "https://picaapi.picacomic.com/"
    const val GO2778 = "https://picaapi.go2778.com/"
    val default: String get() = PicaApiHosts.GO2778
}
