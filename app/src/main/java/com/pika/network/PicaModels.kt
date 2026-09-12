package com.pika.network

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 说明：集合型字段一律给默认值。
 * `ignoreUnknownKeys` 只放过「多出来的字段」，不处理**缺失**字段；`coerceInputValues`
 * 也只在属性**已有默认值**时才能把 null 折算掉。此前这些字段零默认值，服务端一次
 * 缺字段/返回 null（灰度、加字段、docs:null）就会抛解析异常，让整页加载失败。
 * 真正的必需标量（如 code）保持严格，避免把真实错误一起吞掉。
 */
@Serializable
data class ApiResponse<T>(
    val code: Int = 0,
    val message: String = "",
    val data: T? = null,
)

@Serializable
data class LoginPayload(
    val email: String,
    val password: String,
)

@Serializable
data class RegisterPayload(
    val email: String,
    val password: String,
    val name: String,
    val gender: String,
    val birthday: String,
    @EncodeDefault @SerialName("question1") val question1: String = "1",
    @EncodeDefault @SerialName("question2") val question2: String = "2",
    @EncodeDefault @SerialName("question3") val question3: String = "3",
    @EncodeDefault @SerialName("answer1") val answer1: String = "4",
    @EncodeDefault @SerialName("answer2") val answer2: String = "5",
    @EncodeDefault @SerialName("answer3") val answer3: String = "6",
)

@Serializable
data class LoginResponse(
    val token: String,
)

@Serializable
data class ImageDetail(
    @SerialName("fileServer") val fileServer: String,
    val path: String,
    @SerialName("originalName") val originalName: String? = null,
) {
    private val normalizedPath: String
        get() = path.split("/").filter { it.isNotEmpty() }.joinToString("/")

    /**
     * 直连地址。
     *
     * 归一化策略：把 fileServer 与 path 两边的 `static` 段各自去重一次，再拼一次——
     * 避免「fileServer 已含 static」且「path 以 static/ 开头」时拼出 `static/static/`（必然 404）。
     */
    val directUrl: String
        get() {
            val fs = fileServer.trimEnd('/')
            if (fs.isBlank()) return ""
            // path 归一化时已去掉前导 '/'；此处再去掉可能与之重复的 "static/" 前缀
            val p = normalizedPath.trimStart('/').removePrefix("static/")
            val base = if (fs.endsWith("/static") || fs.substringAfterLast('/').startsWith("static")) {
                fs
            } else {
                "$fs/static"
            }
            return if (p.isEmpty()) base else "$base/$p"
        }
}

@Serializable
data class Category(
    @SerialName("_id") val id: String? = null,
    val thumb: ImageDetail? = null,
    val title: String = "",
    val description: String = "",
    @SerialName("isWeb") val isWeb: Boolean? = null,
    val active: Boolean? = null,
    val link: String? = null,
)

@Serializable
data class CategoriesResponse(
    val categories: List<Category> = emptyList(),
)

@Serializable
data class Doc(
    @SerialName("_id") val uid: String = "",
    val title: String = "",
    val author: String = "",
    @SerialName("totalViews") val totalViews: Int = 0,
    @SerialName("totalLikes") val totalLikes: Int? = null,
    @SerialName("pagesCount") val pagesCount: Int = 0,
    @SerialName("epsCount") val epsCount: Int = 0,
    val finished: Boolean = false,
    val categories: List<String> = emptyList(),
    val thumb: ImageDetail? = null,
    val id: String? = null,
    @SerialName("likesCount") val likesCount: Int = 0,
    val tags: List<String> = emptyList(),
    @SerialName("updated_at") val updatedAt: String = "",
) {
    /**
     * 详情页路由用的漫画 ID。
     *
     * 判据只允许是「ID 本身是否有效」——此前写成 `thumb?.let { id ?: uid } ?: uid`，
     * 把「有没有封面」当成了「数据来自哪个接口」的隐式开关：列表项恰好缺封面时，
     * 导航 ID 会从 id 静默切换成 _id，可能导致详情 404 / 收藏落到另一本 / 去重失效。
     */
    val comicId: String get() = id?.takeIf { it.isNotBlank() } ?: uid
}

@Serializable
data class ComicsData(
    val docs: List<Doc> = emptyList(),
    val limit: Int = 0,
    val page: Int = 0,
    val pages: Int = 0,
    val total: Int = 0,
)

@Serializable
data class ComicsResponse(
    val comics: ComicsData,
)

@Serializable
data class Creator(
    @SerialName("_id") val id: String = "",
    val gender: String = "",
    val name: String = "",
    val exp: Int = 0,
    val level: Int = 0,
    val role: String = "",
    val avatar: ImageDetail? = null,
    val characters: List<String> = emptyList(),
    val title: String = "",
    val slogan: String? = null,
)

@Serializable
data class Comic(
    @SerialName("_id") val id: String = "",
    @SerialName("_creator") val creator: Creator? = null,
    val title: String = "",
    val description: String = "",
    val thumb: ImageDetail? = null,
    val author: String? = null,
    val categories: List<String> = emptyList(),
    @SerialName("chineseTeam") val chineseTeam: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("pagesCount") val pagesCount: Int = 0,
    @SerialName("epsCount") val epsCount: Int = 0,
    val finished: Boolean = false,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("allowDownload") val allowDownload: Boolean = false,
    @SerialName("allowComment") val allowComment: Boolean = false,
    @SerialName("totalLikes") val totalLikes: Int = 0,
    @SerialName("totalViews") val totalViews: Int = 0,
    @SerialName("totalComments") val totalComments: Int? = null,
    @SerialName("viewsCount") val viewsCount: Int = 0,
    @SerialName("likesCount") val likesCount: Int = 0,
    @SerialName("commentsCount") val commentsCount: Int = 0,
    @SerialName("isFavourite") val isFavourite: Boolean = false,
    @SerialName("isLiked") val isLiked: Boolean = false,
)

@Serializable
data class ComicDetailsResponse(
    val comic: Comic,
)

@Serializable
data class Chapter(
    @SerialName("_id") val uid: String = "",
    val title: String = "",
    val order: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    val id: String = "",
)

@Serializable
data class ChaptersData(
    val docs: List<Chapter> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
    val pages: Int = 0,
)

@Serializable
data class ChaptersResponse(
    val eps: ChaptersData,
)

@Serializable
data class ChapterImage(
    @SerialName("_id") val uid: String = "",
    val id: String? = null,
    val media: ImageDetail? = null,
)

@Serializable
data class ImagesData(
    val docs: List<ChapterImage> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
    val pages: Int = 0,
)

@Serializable
data class ChapterEpisode(
    @SerialName("_id") val id: String = "",
    val title: String = "",
)

@Serializable
data class FetchChapterImagesResponse(
    val pages: ImagesData,
    val ep: ChapterEpisode? = null,
)

@Serializable
data class SearchPayload(
    val keyword: String,
    @EncodeDefault val sort: String = ComicSortTypeName.DD,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val author: String? = null,
    @SerialName("chineseTeam") val chineseTeam: String? = null,
    val uploader: String? = null,
    val finish: Boolean? = null,
)

@Serializable
data class SearchComic(
    @SerialName("_id") val uid: String = "",
    val title: String = "",
    val author: String = "",
    val thumb: ImageDetail? = null,
    val description: String? = null,
    @SerialName("chineseTeam") val chineseTeam: String? = null,
    val finished: Boolean = false,
    @SerialName("totalViews") val totalViews: Int? = null,
    @SerialName("totalLikes") val totalLikes: Int? = null,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    @SerialName("likesCount") val likesCount: Int = 0,
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("created_at") val createdAt: String = "",
) {
    val id: String get() = uid
    fun toDoc(): Doc = Doc(
        uid = uid,
        title = title,
        author = author,
        totalViews = totalViews ?: 0,
        totalLikes = totalLikes,
        pagesCount = 0,
        epsCount = 0,
        finished = finished,
        categories = categories,
        thumb = thumb,
        id = uid,
        likesCount = likesCount,
        tags = tags,
        updatedAt = updatedAt,
    )
}

@Serializable
data class SearchData(
    val docs: List<SearchComic> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
    val pages: Int = 0,
)

@Serializable
data class SearchResponse(
    val comics: SearchData,
)

@Serializable
data class User(
    @SerialName("_id") val id: String = "",
    val birthday: String = "",
    val email: String = "",
    val gender: String = "m",
    val name: String = "",
    val slogan: String = "",
    val title: String = "萌新",
    val verified: Boolean = false,
    val exp: Int = 0,
    val level: Int = 0,
    val characters: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String = "",
    val avatar: ImageDetail? = null,
    @SerialName("isPunched") val isPunched: Boolean = false,
    val character: String = "",
    @SerialName("comicsUploaded") val comicsUploaded: Int = 0,
)

@Serializable
data class UserProfileResponse(
    val user: User? = null,
)

@Serializable
data class ComicRankResponse(
    val comics: List<Doc> = emptyList(),
)

@Serializable
data class HotSearchWordsResponse(
    val keywords: List<String> = emptyList(),
)

@Serializable
data class RandomComicsResponse(
    val comics: List<Doc> = emptyList(),
)

@Serializable
data class ActionResponse(
    val action: String = "",
)

@Serializable
data class RecommendComic(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val author: String = "",
    val thumb: ImageDetail? = null,
    @SerialName("pagesCount") val pagesCount: Int = 0,
    @SerialName("epsCount") val epsCount: Int = 0,
    val finished: Boolean = false,
    val categories: List<String> = emptyList(),
    @SerialName("likesCount") val likesCount: Int = 0,
)

@Serializable
data class RecommendComics(
    val comics: List<RecommendComic> = emptyList(),
)

object ComicSortTypeName {
    const val DD = "dd"
    const val DA = "da"
    const val LD = "ld"
    const val VD = "vd"
}

// ---------- 评论 ----------

@Serializable
data class Comment(
    @SerialName("_id") val uid: String = "",
    val content: String = "",
    @SerialName("_user") val user: Creator? = null,
    @SerialName("_comic") val comic: String = "",
    @SerialName("totalComments") val totalComments: Int? = null,
    @SerialName("isTop") val isTop: Boolean = false,
    val hide: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val id: String? = null,
    @SerialName("likesCount") val likesCount: Int = 0,
    @SerialName("commentsCount") val commentsCount: Int = 0,
    @SerialName("isLiked") val isLiked: Boolean = false,
)

@Serializable
data class CommentsData(
    val docs: List<Comment> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
    val pages: Int = 0,
)

@Serializable
data class CommentsResponse(
    val comments: CommentsData = CommentsData(),
)

@Serializable
data class SubComment(
    @SerialName("_id") val uid: String = "",
    val content: String = "",
    @SerialName("_user") val user: Creator? = null,
    @SerialName("created_at") val createdAt: String = "",
    val hide: Boolean = false,
    val id: String? = null,
    @SerialName("isLiked") val isLiked: Boolean = false,
    @SerialName("isTop") val isTop: Boolean = false,
    @SerialName("likesCount") val likesCount: Int = 0,
    @SerialName("totalComments") val totalComments: Int? = null,
    @SerialName("_comic") val comic: String? = null,
    @SerialName("_parent") val parent: String = "",
)

@Serializable
data class SendCommentPayload(
    val content: String,
)

// ---------- 我的评论 ----------

@Serializable
data class PersonalComicRef(
    @SerialName("_id") val id: String = "",
    val title: String = "",
    val author: String = "",
    val thumb: ImageDetail? = null,
)

@Serializable
data class PersonalComment(
    @SerialName("_id") val uid: String = "",
    val content: String = "",
    @SerialName("_user") val user: Creator? = null,
    @SerialName("_comic") val comic: PersonalComicRef? = null,
    @SerialName("totalComments") val totalComments: Int? = null,
    val hide: Boolean = false,
    @SerialName("created_at") val createdAt: String = "",
    val id: String? = null,
    @SerialName("likesCount") val likesCount: Int = 0,
    @SerialName("commentsCount") val commentsCount: Int = 0,
    @SerialName("isLiked") val isLiked: Boolean = false,
)

@Serializable
data class PersonalCommentsData(
    val docs: List<PersonalComment> = emptyList(),
    val pages: Int = 0,
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
)

@Serializable
data class PersonalCommentsResponse(
    val comments: PersonalCommentsData = PersonalCommentsData(),
)

// ---------- 账号 ----------

@Serializable
data class ForgotPasswordPayload(
    val email: String,
)

@Serializable
data class UpdateProfilePayload(
    val slogan: String? = null,
    val name: String? = null,
)

@Serializable
data class UpdatePasswordPayload(
    @SerialName("old_password") val oldPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class UpdateAvatarPayload(
    val avatar: String,
)

@Serializable
data class UpdateTitlePayload(
    val title: String,
)