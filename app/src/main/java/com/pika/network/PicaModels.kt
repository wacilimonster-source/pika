package com.pika.network

import com.pika.core.pica.PicaApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T,
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

    /** 直连地址 */
    val directUrl: String
        get() = if (fileServer.contains("static")) {
            "$fileServer/$normalizedPath"
        } else {
            "$fileServer/static/$normalizedPath"
        }

    /** web 代理地址 */
    val proxyUrl: String
        get() = directUrl.replaceFirst("picacomic", "go2778")

    fun url(api: PicaApi): String =
        if (api == PicaApi.PICACOMIC) directUrl else proxyUrl
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
    val categories: List<Category>,
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
    val comicId: String get() = thumb?.let { id ?: uid } ?: uid
}

@Serializable
data class ComicsData(
    val docs: List<Doc>,
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
    val docs: List<Chapter>,
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
    val docs: List<ChapterImage>,
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
    val sort: String = ComicSortTypeName.DD,
    val categories: List<String> = emptyList(),
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
    val docs: List<SearchComic>,
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
    val user: User,
)

@Serializable
data class ComicRankResponse(
    val comics: List<Doc>,
)

@Serializable
data class HotSearchWordsResponse(
    val keywords: List<String>,
)

@Serializable
data class RandomComicsResponse(
    val comics: List<Doc>,
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
    val comics: List<RecommendComic>,
)

object ComicSortTypeName {
    const val DD = "dd"
    const val DA = "da"
    const val LD = "ld"
    const val VD = "vd"
}