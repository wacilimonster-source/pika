package com.pika.core.source

import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicDetail
import com.pika.core.model.ComicPage
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.PageResult
import com.pika.data.SourcePrefs
import com.pika.network.JmAlbumSummary
import com.pika.network.JmClient

/**
 * 禁漫源：包装 JmClient（移动端 v3 API）。
 * 注意：禁漫镜像域名/风控变动频繁，请求失败时优先检查 baseUrl 与 token。
 */
class JmcomicSource : Source {

    override val type: SourceType = SourceType.JMCOMIC

    override val isLoggedIn: Boolean
        get() = !SourcePrefs.current().jmToken.isNullOrEmpty()

    override suspend fun login(email: String, password: String) {
        val token = JmClient.login(email, password)
        SourcePrefs.current().setJmLogin(token)
    }

    override suspend fun logout() {
        SourcePrefs.current().clearJmLogin()
    }

    override suspend fun categories(): List<ComicCategory> {
        return runCatching { JmClient.categories() }.getOrDefault(emptyList())
            .map { ComicCategory(id = it.title, title = it.name.ifBlank { it.title }, coverUrl = it.cover) }
    }

    /** 禁漫列表接口不支持服务端排序，仅客户端按喜欢/观看数重排 */
    override val supportedSorts: List<ComicSort> = listOf(ComicSort.LD, ComicSort.VD)

    override suspend fun browse(
        page: Int,
        category: String?,
        sort: ComicSort,
        author: String?,
    ): PageResult<ComicSummary> {
        val data = JmClient.commendList(page = page, category = category)
        return PageResult(
            items = data.albums.map { it.toSummary() },
            page = data.pagination.page.coerceAtLeast(1),
            pages = data.pagination.totalPage.coerceAtLeast(1),
        )
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
        val data = JmClient.search(keyword, page)
        return PageResult(
            items = data.albums.map { it.toSummary() },
            page = data.pagination.page.coerceAtLeast(1),
            pages = data.pagination.totalPage.coerceAtLeast(1),
        )
    }

    override suspend fun hotWords(): List<String> = emptyList()

    override suspend fun comicDetail(id: String): ComicDetail {
        val data = JmClient.album(id)
        val a = data.album
        return ComicDetail(
            id = a.id.ifBlank { id },
            title = a.name,
            author = a.author,
            description = a.description,
            coverUrl = a.cover,
            categories = a.series,
            tags = a.tags,
            finished = a.finished,
            pagesCount = 0,
            epsCount = data.photos.size,
            totalViews = a.totalViews,
            totalLikes = a.totalLikes,
        )
    }

    override suspend fun chapters(id: String): List<ComicChapter> {
        val data = JmClient.album(id)
        val photos = data.photos.ifEmpty { data.pages }
        return photos.mapIndexed { index, p ->
            ComicChapter(
                id = p.id,
                title = p.name.ifBlank { "第 ${index + 1} 话" },
                order = p.index.takeIf { it > 0 } ?: (index + 1),
            )
        }
    }

    override suspend fun chapterPages(comicId: String, order: Int): List<ComicPage> {
        // 取 albums 详情拿 images 元数据映射表
        val detail = JmClient.album(comicId)
        val images = detail.images
        val out = mutableListOf<ComicPage>()
        var pageIndex = 1
        while (true) {
            val resp = JmClient.albumPage(comicId, order, pageIndex)
            resp.images.forEach { metaId ->
                val meta = images[metaId]
                if (meta != null) {
                    out += ComicPage(index = out.size, imageUrl = meta.directUrl)
                }
            }
            val total = resp.page.allPageCount.coerceAtLeast(resp.images.size)
            pageIndex++
            if (out.size >= total || resp.images.isEmpty()) break
            if (pageIndex > 200) break // 防死循环
        }
        return out
    }
}

private fun JmAlbumSummary.toSummary() = ComicSummary(
    id = id,
    title = name,
    author = author,
    coverUrl = cover,
    finished = finished,
    totalViews = totalViews,
    totalLikes = totalLikes,
)