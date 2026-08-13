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
    /** 更新时间（"yyyy-MM-dd..." ISO 前缀，用于日期范围筛选；源不支持时为空） */
    val updatedAt: String = "",
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

/** 排序方式（哔咔服务端排序；禁漫由客户端对已加载列表重排） */
enum class ComicSort(val label: String) {
    DD("新到旧"),
    DA("旧到新"),
    LD("最多喜欢"),
    VD("最多观看"),
}

/** 连载状态筛选 */
enum class ComicStatus(val label: String) {
    ALL("全部"),
    FINISHED("已完结"),
    ONGOING("连载中"),
}

/** 更新日期范围（年/月，含起止）；哔咔服务端无日期查询参数，客户端按更新时间过滤已加载数据 */
data class ComicDateRange(
    val fromYear: Int,
    val fromMonth: Int,
    val toYear: Int,
    val toMonth: Int,
) {
    fun label(): String = "$fromYear-${fromMonth.toString().padStart(2, '0')} ~ $toYear-${toMonth.toString().padStart(2, '0')}"

    fun matches(updatedAt: String): Boolean {
        if (updatedAt.length < 7) return false
        val year = updatedAt.substring(0, 4).toIntOrNull() ?: return false
        val month = updatedAt.substring(5, 7).toIntOrNull() ?: return false
        val afterFrom = year > fromYear || (year == fromYear && month >= fromMonth)
        val beforeTo = year < toYear || (year == toYear && month <= toMonth)
        return afterFrom && beforeTo
    }
}