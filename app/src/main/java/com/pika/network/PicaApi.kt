package com.pika.network

import com.pika.core.pica.ComicRankType
import com.pika.core.pica.ComicSortType
import com.pika.core.pica.PicaApi
import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.QueryMap

interface PicaApi {
    @POST("auth/sign-in")
    suspend fun login(@Body body: LoginPayload): ApiResponse<LoginResponse>

    @POST("auth/register")
    suspend fun register(@Body body: RegisterPayload): ApiResponse<JsonObject>

    @GET("categories")
    suspend fun categories(): ApiResponse<CategoriesResponse>

    @GET("comics")
    suspend fun comics(@QueryMap params: Map<String, String>): ApiResponse<ComicsResponse>

    @GET("comics/{id}")
    suspend fun comic(@Path("id") id: String): ApiResponse<ComicDetailsResponse>

    @GET("comics/{id}/eps")
    suspend fun chapters(
        @Path("id") id: String,
        @Query("page") page: Int,
    ): ApiResponse<ChaptersResponse>

    @GET("comics/{id}/order/{order}/pages")
    suspend fun chapterImages(
        @Path("id") id: String,
        @Path("order") order: Int,
        @Query("page") page: Int,
    ): ApiResponse<FetchChapterImagesResponse>

    @POST("comics/advanced-search")
    suspend fun search(
        @Query("page") page: Int,
        @Body body: SearchPayload,
    ): ApiResponse<SearchResponse>

    @GET("users/profile")
    suspend fun profile(): ApiResponse<UserProfileResponse>

    @GET("users/favourite")
    suspend fun favourites(@QueryMap params: Map<String, String>): ApiResponse<ComicsResponse>

    @GET("comics/leaderboard")
    suspend fun leaderboard(@QueryMap params: Map<String, String>): ApiResponse<ComicRankResponse>

    @GET("keywords")
    suspend fun hotSearch(): ApiResponse<HotSearchWordsResponse>

    @GET("comics/random")
    suspend fun random(): ApiResponse<RandomComicsResponse>

    @POST("users/punch-in")
    suspend fun punchIn(): ApiResponse<JsonObject>

    @POST("comics/{id}/favourite")
    suspend fun favorite(@Path("id") id: String): ApiResponse<ActionResponse>

    @GET("comics/{id}/recommendation")
    suspend fun recommendation(@Path("id") id: String): ApiResponse<RecommendComics>

    // ---------- 评论 ----------

    @GET("comics/{id}/comments")
    suspend fun comments(
        @Path("id") id: String,
        @Query("page") page: Int,
    ): ApiResponse<CommentsResponse>

    @POST("comics/{id}/comments")
    suspend fun sendComment(
        @Path("id") id: String,
        @Body body: SendCommentPayload,
    ): ApiResponse<Comment>

    @POST("comments/{id}")
    suspend fun replyComment(
        @Path("id") id: String,
        @Body body: SendCommentPayload,
    ): ApiResponse<Comment>

    @GET("comments/{id}/childrens")
    suspend fun commentChildren(
        @Path("id") id: String,
        @Query("page") page: Int,
    ): ApiResponse<CommentsResponse>

    @GET("users/my-comments")
    suspend fun myComments(@Query("page") page: Int): ApiResponse<PersonalCommentsResponse>

    // ---------- 账号 ----------

    @POST("auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordPayload): ApiResponse<JsonObject>

    @PUT("users/profile")
    suspend fun updateProfile(@Body body: UpdateProfilePayload): ApiResponse<JsonObject>

    @PUT("users/password")
    suspend fun updatePassword(@Body body: UpdatePasswordPayload): ApiResponse<JsonObject>

    @PUT("users/avatar")
    suspend fun updateAvatar(@Body body: UpdateAvatarPayload): ApiResponse<JsonObject>

    @PUT("users/{id}/title")
    suspend fun updateTitle(
        @Path("id") id: String,
        @Body body: UpdateTitlePayload,
    ): ApiResponse<JsonObject>
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
    "tt" to type.name.lowercase(),
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