package com.pika.core.model

import com.pika.core.source.ComicRef
import com.pika.core.source.SourceType
import kotlinx.serialization.Serializable

/**
 * 数据源无关的漫画模型：所有源（哔咔/禁漫）统一映射成这些类型供 UI 使用。
 */

/** 列表项（卡片） */
@Serializable
data class ComicSummary(
    val id: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val finished: Boolean = false,
    val totalViews: Long = 0,
    val totalLikes: Long = 0,
    /** 标签（用于客户端标签筛选；源不支持或列表接口不返回时为空） */
    val tags: List<String> = emptyList(),
    /** 更新时间（"yyyy-MM-dd..." ISO 前缀，用于日期范围筛选；源不支持时为空） */
    val updatedAt: String = "",
    /**
     * 产出这条数据的源，由 SourceManager 统一盖章（见 ComicRef）。
     * 默认哔咔：历史 FollowFeedCache 里没有该字段，按哔咔解释。
     */
    val source: SourceType = SourceType.PICACG,
) {
    /** 作品标识：列表点击、路由参数、本地记账都用它 */
    val ref: String get() = ComicRef.of(source, id)
}

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
    val updatedAt: String = "",
    val createdAt: String = "",
    /**
     * 当前用户是否已收藏（源提供时填充）。
     * 缺省 false：不支持的源保持原行为；支持的源用它初始化详情页心形状态，
     * 避免「已收藏的作品显示为未收藏、取消收藏需要点两次」。
     */
    val isFavourite: Boolean = false,
    /** 产出该详情的源，由 SourceManager 统一盖章（见 ComicRef） */
    val source: SourceType = SourceType.PICACG,
) {
    /** 作品标识：阅读器路由、本地进度记账用 */
    val ref: String get() = ComicRef.of(source, id)
}

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

/** 排序方式（哔咔服务端排序；禁漫由客户端对已加载列表重排） */
enum class ComicSort(val label: String) {
    DD("新到旧"),
    DA("旧到新"),
    LD("最多喜欢"),
    VD("最多观看"),
}

/** 按排序方式对已加载列表重排（多词交集等本地聚合场景） */
fun List<ComicSummary>.sortedByComicSort(sort: ComicSort): List<ComicSummary> = when (sort) {
    ComicSort.DD -> sortedByDescending { it.updatedAt }
    ComicSort.DA -> sortedBy { it.updatedAt }
    ComicSort.LD -> sortedByDescending { it.totalLikes }
    ComicSort.VD -> sortedByDescending { it.totalViews }
}

/** 连载状态筛选 */
enum class ComicStatus(val label: String) {
    ALL("全部"),
    FINISHED("已完结"),
    ONGOING("连载中"),
}

/** 用户（评论者 / 本人资料） */
data class ComicUser(
    val id: String = "",
    val name: String = "",
    val avatarUrl: String? = null,
    val level: Int = 0,
    val exp: Int = 0,
    val title: String = "",
    val slogan: String = "",
    val email: String = "",
    val gender: String = "",
    val birthday: String = "",
    val characters: List<String> = emptyList(),
    val createdAt: String = "",
)

/** 漫画评论 */
data class ComicComment(
    val id: String,
    val content: String,
    val user: ComicUser? = null,
    val createdAt: String = "",
    val likesCount: Int = 0,
    val isLiked: Boolean = false,
    val commentsCount: Int = 0,
    val isTop: Boolean = false,
)

/** 我的评论 */
data class MyComicComment(
    val id: String,
    val content: String,
    val comicId: String = "",
    val comicTitle: String = "",
    val createdAt: String = "",
    val likesCount: Int = 0,
)

/** 每日签到结果（禁漫源独有；不支持的源抛异常） */
data class DailyCheckIn(
    val checkedIn: Boolean = false,
    val consecutiveDays: Int = 0,
    val message: String = "",
)