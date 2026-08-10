package com.pika.core.model

/**
 * 数据源无关的漫画模型：所有源（哔咔/禁漫）统一映射成这些类型供 UI 使用。
 */

/** 列表项（卡片） */
data class ComicSummary(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val finished: Boolean = false,
    val totalViews: Long = 0,
    val totalLikes: Long = 0,
)

/** 分类 */
data class ComicCategory(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
)

/** 详情页 */
data class ComicDetail(
    val id: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String?,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val finished: Boolean = false,
    val pagesCount: Int = 0,
    val epsCount: Int = 0,
    val totalViews: Long = 0,
    val totalLikes: Long = 0,
    val commentsCount: Long = 0,
)

/** 章节 */
data class ComicChapter(
    val id: String,
    val title: String,
    val order: Int,
)

/** 阅读页 */
data class ComicPage(
    val index: Int,
    val imageUrl: String,
)

/** 分页结果 */
data class PageResult<T>(
    val items: List<T>,
    val page: Int = 1,
    val pages: Int = 1,
)