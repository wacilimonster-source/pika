package com.pika.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 禁漫（jmcomic）移动端 v3 API 的响应模型。
 * 字段冗余但均带默认值，配合 ignoreUnknownKeys 容错。
 */

@Serializable
data class JmLoginResponse(
    val code: Int = 0,
    val token: String = "",
)

@Serializable
data class JmCategory(
    val title: String = "",
    val name: String = "",
    val cover: String? = null,
)

@Serializable
data class JmCategoriesResponse(
    val code: Int = 0,
    val categories: List<JmCategory> = emptyList(),
)

@Serializable
data class JmPagination(
    val page: Int = 0,
    @SerialName("total_page") val totalPage: Int = 1,
)

@Serializable
data class JmAlbumSummary(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val cover: String? = null,
    val description: String = "",
    val finished: Boolean = false,
    @SerialName("total_views") val totalViews: Long = 0,
    @SerialName("total_likes") val totalLikes: Long = 0,
)

@Serializable
data class JmAlbumListResponse(
    val code: Int = 0,
    val albums: List<JmAlbumSummary> = emptyList(),
    val pagination: JmPagination = JmPagination(),
)

@Serializable
data class JmSearchResponse(
    val code: Int = 0,
    val albums: List<JmAlbumSummary> = emptyList(),
    val pagination: JmPagination = JmPagination(),
)

@Serializable
data class JmSearchPayload(
    @SerialName("search_query") val query: String,
)

@Serializable
data class JmPhoto(
    val id: String = "",
    val index: Int = 0,
    val name: String = "",
    val num: Int = 0,
)

@Serializable
data class JmImage(
    val host: String = "",
    val path: String = "",
    val folder: String = "",
    val name: String = "",
    @SerialName("original_name") val originalName: String = "",
) {
    val ext: String
        get() = originalName.substringAfterLast('.', "")

    /** 图片直链 */
    val directUrl: String
        get() = buildString {
            append("https://").append(host).append("/media/").append(folder).append('/').append(name)
            if (ext.isNotEmpty() && !name.endsWith(".$ext")) append('.').append(ext)
        }
}

@Serializable
data class JmAlbumDetail(
    val id: String = "",
    val name: String = "",
    val author: String = "",
    val description: String = "",
    val cover: String? = null,
    val finished: Boolean = false,
    val tags: List<String> = emptyList(),
    val series: List<String> = emptyList(),
    @SerialName("total_views") val totalViews: Long = 0,
    @SerialName("total_likes") val totalLikes: Long = 0,
)

@Serializable
data class JmAlbumResponse(
    val code: Int = 0,
    val album: JmAlbumDetail = JmAlbumDetail(),
    val photos: List<JmPhoto> = emptyList(),
    val pages: List<JmPhoto> = emptyList(),
    val images: Map<String, JmImage> = emptyMap(),
)

@Serializable
data class JmAlbumPageResponse(
    val code: Int = 0,
    val album: JmAlbumDetail = JmAlbumDetail(),
    val images: List<String> = emptyList(),
    val page: JmAlbumPageInfo = JmAlbumPageInfo(),
)

@Serializable
data class JmAlbumPageInfo(
    val pageNum: Int = 0,
    val allPageCount: Int = 0,
    @SerialName("allPhotoCount") val allPhotoCount: Int = 0,
)