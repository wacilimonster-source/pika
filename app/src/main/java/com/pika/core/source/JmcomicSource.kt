package com.pika.core.source

import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicComment
import com.pika.core.model.ComicDetail
import com.pika.core.model.ComicPage
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.ComicUser
import com.pika.core.model.PageResult
import com.pika.data.SourcePrefs
import com.pika.network.JmClient
import com.pika.network.JmCrypto
import com.pika.network.JmException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 禁漫源：包装 JmClient（移动端 API，实测修复版）。
 * 说明：本文件只实现免登录即可用的能力；收藏/签到/历史/个人资料/发评论等需登录
 * 端点（端点已确认存在）沿用 Source 默认实现（抛 UnsupportedOperationException），
 * 等接入真实账号后再补。
 */
class JmcomicSource : Source {

    override val type: SourceType = SourceType.JMCOMIC

    override val isLoggedIn: Boolean
        get() = !SourcePrefs.current().jmToken.isNullOrEmpty()

    override suspend fun login(email: String, password: String) {
        val session = JmClient.login(email, password)
        SourcePrefs.current().setJmLogin(session)
    }

    override suspend fun logout() {
        runCatching { JmClient.logout() }
        SourcePrefs.current().clearJmLogin()
    }

    override suspend fun categories(): List<ComicCategory> {
        return runCatching { JmClient.categories() }.getOrDefault(JmCategoriesFallback)
            .data.categories.map {
                ComicCategory(
                    id = it.slug.ifBlank { it.id },
                    title = it.name.ifBlank { it.id },
                    coverUrl = null,
                )
            }
    }

    /** 服务端支持 mr/mv/tf 排序：DD->mr, LD->tf, VD->mv（DA 无升序参数，沿用 mr） */
    override val supportedSorts: List<ComicSort> = listOf(ComicSort.DD, ComicSort.LD, ComicSort.VD)

    override suspend fun browse(
        page: Int,
        category: String?,
        sort: ComicSort,
        author: String?,
        tag: String?,
    ): PageResult<ComicSummary> {
        val data = runCatching { JmClient.browse(page, category, sort) }.getOrNull()
            ?: return PageResult(emptyList(), page, page)
        val items = data.data.content.map { it.toSummary() }
        // 服务端 total 恒为 10000（硬上限，不可信），按当页条数决定是否还有下一页
        val pages = if (items.size < 80) page else page + 1
        return PageResult(items = items, page = page, pages = pages)
    }

    override suspend fun search(
        keyword: String,
        page: Int,
        sort: ComicSort,
        categories: List<String>,
        tags: List<String>,
        author: String?,
        chineseTeam: String?,
        uploader: String?,
        finished: Boolean?,
    ): PageResult<ComicSummary> {
        val data = runCatching { JmClient.search(keyword, page, sort) }.getOrNull()
            ?: return PageResult(emptyList(), page, page)
        val items = data.data.content.map { it.toSummary() }
        val pages = if (items.size < 80) page else page + 1
        return PageResult(items = items, page = page, pages = pages)
    }

    override suspend fun hotWords(): List<String> = emptyList()

    override suspend fun comicDetail(id: String): ComicDetail {
        val a = runCatching { JmClient.album(id) }.getOrNull()?.data
            ?: throw JmException("禁漫详情加载失败")
        val albumId = a.id.ifBlank { id }
        return ComicDetail(
            id = albumId,
            title = a.name,
            author = a.author,
            description = a.description,
            coverUrl = JmCrypto.coverUrl(albumId),
            categories = emptyList(),
            tags = a.tags,
            finished = false,
            pagesCount = a.totalPhotos,
            epsCount = a.series.size.coerceAtLeast(1),
            totalViews = a.totalViews,
            totalLikes = a.likes,
            commentsCount = a.commentTotal.toLong(),
            createdAt = a.addtime,
        )
    }

    override suspend fun chapters(id: String): List<ComicChapter> {
        val a = runCatching { JmClient.album(id) }.getOrNull()?.data ?: return emptyList()
        val albumId = a.id.ifBlank { id }
        return if (a.series.isNotEmpty()) {
            a.series.mapIndexed { i, s ->
                ComicChapter(id = s.id, title = s.name.ifBlank { "第 ${i + 1} 话" }, order = i + 1)
            }
        } else {
            // 单章本子：series 为空，章节即本子自身
            listOf(ComicChapter(id = albumId, title = a.name.ifBlank { "第 1 话" }, order = 1))
        }
    }

    override suspend fun chapterPages(comicId: String, order: Int): List<ComicPage> {
        val a = runCatching { JmClient.album(comicId) }.getOrNull()?.data ?: return emptyList()
        val albumId = a.id.ifBlank { comicId }
        // order(1-based) -> 章节 photo id；单章本子回退用 album id
        val photoId = a.series.getOrNull(order - 1)?.id ?: albumId
        val chapter = runCatching { JmClient.chapter(photoId) }.getOrNull()?.data ?: return emptyList()
        return chapter.images.mapIndexed { i, filename ->
            ComicPage(index = i, imageUrl = JmCrypto.imageUrl(photoId, filename))
        }
    }

    /** 排行榜（日/周/月）：H24->mv_t, D7->mv_w, D30->mv_m */
    override suspend fun rank(type: String): List<ComicSummary> {
        val order = when (type) {
            "H24" -> "mv_t"
            "D7" -> "mv_w"
            "D30" -> "mv_m"
            else -> "mv_t"
        }
        val data = runCatching { JmClient.rankList(order) }.getOrNull() ?: return emptyList()
        return data.data.content.map { it.toSummary() }
    }

    /** 相关推荐：详情的 related_list（约 20 条） */
    override suspend fun recommendations(id: String): List<ComicSummary> {
        val a = runCatching { JmClient.album(id) }.getOrNull()?.data ?: return emptyList()
        return a.relatedList.map {
            ComicSummary(
                id = it.id,
                title = it.name,
                author = it.author,
                coverUrl = JmCrypto.coverUrl(it.id),
            )
        }
    }

    /** 评论：/forum?mode=all&aid= */
    override suspend fun comments(comicId: String, page: Int): PageResult<ComicComment> {
        val data = runCatching { JmClient.comments(comicId, page) }.getOrNull()?.data
            ?: return PageResult(emptyList(), page, page)
        val items = data.list.map {
            ComicComment(
                id = it.id,
                content = it.content,
                user = ComicUser(name = it.nickname.ifBlank { it.username }),
                createdAt = it.addtime,
            )
        }
        val pages = if (data.list.size < 20) page else page + 1
        return PageResult(items = items, page = page, pages = pages)
    }

    // ---------- 需登录：收藏 / 签到 / 历史 ----------

    override suspend fun favourites(page: Int): PageResult<ComicSummary> {
        val data = runCatching { JmClient.favorites(page) }.getOrNull()
            ?: return PageResult(emptyList(), page, page)
        val items = data.data.content.map { it.toSummary() }
        val pages = if (items.size < 80) page else page + 1
        return PageResult(items = items, page = page, pages = pages)
    }

    override suspend fun favourite(comicId: String, add: Boolean): Boolean =
        runCatching { JmClient.favoriteToggle(comicId, add) }.getOrDefault(false)

    /** 签到：查询状态 + 执行签到（JM 独有）。返回连续天数与提示。 */
    override suspend fun dailyCheckIn(): com.pika.core.model.DailyCheckIn {
        val resp = runCatching { JmClient.dailyCheckIn() }.getOrNull() ?: return com.pika.core.model.DailyCheckIn()
        val d = resp.data
        return com.pika.core.model.DailyCheckIn(
            checkedIn = d?.isCheckIn ?: false,
            consecutiveDays = d?.checkInDays ?: d?.days ?: 0,
            message = resp.errorMsg ?: "",
        )
    }

    /** 云端浏览历史（JM 独有） */
    override suspend fun cloudHistory(page: Int): PageResult<ComicSummary> {
        val data = runCatching { JmClient.watchList(page) }.getOrNull()
            ?: return PageResult(emptyList(), page, page)
        val items = data.data.content.map { it.toSummary() }
        val pages = if (items.size < 80) page else page + 1
        return PageResult(items = items, page = page, pages = pages)
    }
}

private val JmCategoriesFallback = com.pika.network.JmCategoriesResponse()

private fun com.pika.network.JmAlbumSummary.toSummary() = ComicSummary(
    id = id,
    title = name,
    author = author,
    coverUrl = JmCrypto.coverUrl(id),
    finished = false,
    updatedAt = if (adddate.isNotBlank()) adddate else formatDate(updateAt),
)

private fun formatDate(ts: Long): String =
    if (ts <= 0) "" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts * 1000))
