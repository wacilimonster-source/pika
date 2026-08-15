package com.pika.core.source

import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicComment
import com.pika.core.model.ComicDetail
import com.pika.core.model.ComicPage
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.ComicUser
import com.pika.core.model.MyComicComment
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
        tag: String? = null,
    ): PageResult<ComicSummary>

    /** 当前源支持的排序方式（不在列表内的排序会回退到默认） */
    val supportedSorts: List<ComicSort>
        get() = ComicSort.entries.toList()

    /** 关键词搜索（额外筛选参数：排序/分类/标签/作者/汉化组/上传者/完结状态；源不支持时忽略） */
    suspend fun search(
        keyword: String,
        page: Int,
        sort: ComicSort = ComicSort.DD,
        categories: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        author: String? = null,
        chineseTeam: String? = null,
        uploader: String? = null,
        finished: Boolean? = null,
    ): PageResult<ComicSummary>

    /** 热搜词（可能为空） */
    suspend fun hotWords(): List<String>

    /** 官方标签词表（可能为空，表示源不支持标签筛选） */
    suspend fun tags(): List<String> = emptyList()

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

    /** 排行榜（默认源不支持） */
    suspend fun rank(type: String): List<ComicSummary> =
        throw UnsupportedOperationException("当前源不支持排行榜")

    /** 随机漫画（默认源不支持） */
    suspend fun randomComics(): List<ComicSummary> =
        throw UnsupportedOperationException("当前源不支持随机推荐")

    /** 相关推荐（默认源不支持） */
    suspend fun recommendations(id: String): List<ComicSummary> =
        throw UnsupportedOperationException("当前源不支持相关推荐")

    /** 我的资料（默认源不支持） */
    suspend fun profile(): ComicUser =
        throw UnsupportedOperationException("当前源不支持个人资料")

    // ---------- 评论 ----------

    suspend fun comments(comicId: String, page: Int): PageResult<ComicComment> =
        throw UnsupportedOperationException("当前源不支持评论")

    suspend fun sendComment(comicId: String, content: String): Unit =
        throw UnsupportedOperationException("当前源不支持评论")

    suspend fun replyComment(commentId: String, content: String): Unit =
        throw UnsupportedOperationException("当前源不支持评论")

    suspend fun commentChildren(commentId: String, page: Int): PageResult<ComicComment> =
        throw UnsupportedOperationException("当前源不支持评论")

    suspend fun myComments(page: Int): PageResult<MyComicComment> =
        throw UnsupportedOperationException("当前源不支持评论")

    // ---------- 账号管理 ----------

    /** 忘记密码：发送重置邮件（默认源不支持） */
    suspend fun forgotPassword(email: String): Unit =
        throw UnsupportedOperationException("当前源不支持")

    /** 修改简介（默认源不支持） */
    suspend fun updateSlogan(slogan: String): Unit =
        throw UnsupportedOperationException("当前源不支持")

    /** 修改密码（默认源不支持） */
    suspend fun updatePassword(oldPassword: String, newPassword: String): Unit =
        throw UnsupportedOperationException("当前源不支持")

    /** 上传头像（base64 图片数据；默认源不支持） */
    suspend fun updateAvatar(base64: String): Unit =
        throw UnsupportedOperationException("当前源不支持")

    /** 修改称号（默认源不支持） */
    suspend fun updateTitle(title: String): Unit =
        throw UnsupportedOperationException("当前源不支持")
}