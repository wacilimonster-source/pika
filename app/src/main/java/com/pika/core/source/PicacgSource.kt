package com.pika.core.source

import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicDetail
import com.pika.core.model.ComicPage
import com.pika.core.model.ComicSummary
import com.pika.core.model.PageResult
import com.pika.data.SourcePrefs
import com.pika.network.Category
import com.pika.network.Comic
import com.pika.network.Doc
import com.pika.network.PicaClient
import com.pika.network.SearchPayload
import com.pika.network.comicsQuery

/**
 * 哔咔源：包装 PicaClient，映射为统一模型。
 */
class PicacgSource : Source {

    override val type: SourceType = SourceType.PICACG

    override val isLoggedIn: Boolean
        get() = !SourcePrefs.current().picaToken.isNullOrEmpty()

    override suspend fun login(email: String, password: String) {
        val data = PicaClient.safeCall {
            PicaClient.api.login(com.pika.network.LoginPayload(email = email, password = password))
        }
        SourcePrefs.current().setPicaLogin(token = data.token, email = email)
    }

    override suspend fun logout() {
        SourcePrefs.current().clearPicaLogin()
    }

    override suspend fun categories(): List<ComicCategory> {
        val data = PicaClient.safeCall { PicaClient.api.categories() }
        return data.categories
            .filter { it.active != false }
            .map { it.toComicCategory() }
    }

    override suspend fun browse(page: Int, category: String?): PageResult<ComicSummary> {
        val data = PicaClient.safeCall {
            PicaClient.api.comics(comicsQuery(page = page, category = category))
        }
        return PageResult(
            items = data.comics.docs.map { it.toSummary() },
            page = data.comics.page,
            pages = data.comics.pages.coerceAtLeast(1),
        )
    }

    override suspend fun search(keyword: String, page: Int): PageResult<ComicSummary> {
        val data = PicaClient.safeCall {
            PicaClient.api.search(
                page = page,
                body = SearchPayload(keyword = keyword),
            )
        }
        return PageResult(
            items = data.comics.docs.map { it.toDoc().toSummary() },
            page = data.comics.page,
            pages = data.comics.pages.coerceAtLeast(1),
        )
    }

    override suspend fun hotWords(): List<String> {
        val data = PicaClient.safeCall { PicaClient.api.hotSearch() }
        return data.keywords
    }

    override suspend fun comicDetail(id: String): ComicDetail {
        val data = PicaClient.safeCall { PicaClient.api.comic(id) }
        return data.comic.toDetail()
    }

    override suspend fun chapters(id: String): List<ComicChapter> {
        val first = PicaClient.safeCall { PicaClient.api.chapters(id, page = 1) }
        val docs = mutableListOf<com.pika.network.Chapter>()
        docs += first.eps.docs
        var page = 1
        while (page < first.eps.pages) {
            page++
            val more = PicaClient.safeCall { PicaClient.api.chapters(id, page) }
            docs += more.eps.docs
            if (more.eps.docs.isEmpty()) break
        }
        return docs.mapIndexed { index, c ->
            com.pika.core.model.ComicChapter(
                id = c.uid.ifBlank { c.id },
                title = c.title,
                order = c.order.takeIf { it > 0 } ?: (index + 1),
            )
        }
    }

    override suspend fun chapterPages(comicId: String, order: Int): List<ComicPage> {
        val first = PicaClient.safeCall { PicaClient.api.chapterImages(comicId, order, page = 1) }
        val out = mutableListOf<ComicPage>()
        var page = 1
        while (page <= first.pages.pages) {
            val data = if (page == 1) first else {
                PicaClient.safeCall { PicaClient.api.chapterImages(comicId, order, page) }
            }
            data.pages.docs.forEachIndexed { i, doc ->
                doc.media?.let { m ->
                    out += ComicPage(index = out.size, imageUrl = m.directUrl)
                }
            }
            if (data.pages.docs.isEmpty()) break
            page++
        }
        return out
    }

    override suspend fun favourite(comicId: String, add: Boolean): Boolean {
        val resp = PicaClient.safeCall { PicaClient.api.favorite(comicId) }
        return resp.action.isNotBlank()
    }

    override suspend fun favourites(page: Int): PageResult<ComicSummary> {
        val data = PicaClient.safeCall {
            PicaClient.api.favourites(
                com.pika.network.favouriteQuery(page, com.pika.core.pica.ComicSortType.DD),
            )
        }
        return PageResult(
            items = data.comics.docs.map { it.toSummary() },
            page = data.comics.page,
            pages = data.comics.pages.coerceAtLeast(1),
        )
    }
}

// ---------- 映射 ----------

private fun Category.toComicCategory() = ComicCategory(
    id = title,
    title = title,
    coverUrl = thumb?.directUrl,
)

private fun Doc.toSummary() = ComicSummary(
    id = comicId,
    title = title,
    author = author,
    coverUrl = thumb?.directUrl,
    finished = finished,
    totalViews = totalViews.toLong(),
    totalLikes = (totalLikes ?: likesCount).toLong(),
)

private fun Comic.toDetail() = ComicDetail(
    id = id,
    title = title,
    author = author ?: "",
    description = description,
    coverUrl = thumb?.directUrl,
    categories = categories,
    tags = tags,
    finished = finished,
    pagesCount = pagesCount,
    epsCount = epsCount,
    totalViews = totalViews.toLong(),
    totalLikes = totalLikes.toLong(),
    commentsCount = commentsCount.toLong(),
)