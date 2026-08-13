package com.pika.core.source

import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicDetail
import com.pika.core.model.ComicPage
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.PageResult

/**
 * 数据源统一接口：哔咔 / 禁漫 各自实现。
 * UI 与 ViewModel 只依赖此接口，感知不到具体源。
 */
interface Source {
    val type: SourceType

    /** 当前源是否已登录（各自账号体系独立） */
    val isLoggedIn: Boolean

    /** 登录（各源账号体系独立，凭邮箱 + 密码） */
    suspend fun login(email: String, password: String)

    /** 注册（默认源不支持）。注册成功即已登录。 */
    suspend fun register(
        email: String,
        password: String,
        name: String,
        gender: String = "m",
    ): Unit =
        throw UnsupportedOperationException("当前源不支持注册")

    /** 退出登录 */
    suspend fun logout()

    /** 分类列表（可能为空，表示不支持分类） */
    suspend fun categories(): List<ComicCategory>

    /** 内容流（categorie 为空表示全部 / 源默认流；author 非空时按作者筛选） */
    suspend fun browse(
        page: Int,
        category: String?,
        sort: ComicSort = ComicSort.DD,
        author: String? = null,
    ): PageResult<ComicSummary>

    /** 当前源支持的排序方式（不在列表内的排序会回退到默认） */
    val supportedSorts: List<ComicSort>
        get() = ComicSort.entries.toList()

    /** 关键词搜索 */
    suspend fun search(keyword: String, page: Int): PageResult<ComicSummary>

    /** 热搜词（可能为空） */
    suspend fun hotWords(): List<String>

    /** 漫画详情 */
    suspend fun comicDetail(id: String): ComicDetail

    /** 章节列表（按阅读顺序，order 从 1 递增） */
    suspend fun chapters(id: String): List<ComicChapter>

    /** 某章全部图片页 */
    suspend fun chapterPages(comicId: String, order: Int): List<ComicPage>

    /** 收藏 / 取消收藏（默认源不支持） */
    suspend fun favourite(comicId: String, add: Boolean): Boolean =
        throw UnsupportedOperationException("当前源不支持收藏")

    /** 收藏列表（分页；默认源不支持） */
    suspend fun favourites(page: Int): PageResult<ComicSummary> =
        throw UnsupportedOperationException("当前源不支持收藏")
}