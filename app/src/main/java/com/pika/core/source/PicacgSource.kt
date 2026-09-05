package com.pika.core.source

import com.pika.core.log.LogStore
import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicDetail
import com.pika.core.model.ComicPage
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.PageResult
import com.pika.data.SourcePrefs
import com.pika.network.Category
import com.pika.network.Comic
import com.pika.network.Doc
import com.pika.network.PicaClient
import com.pika.network.SearchPayload
import com.pika.network.comicsQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
        // 保存账号凭据，供登出后会话失效时静默重登/一键填充
        com.pika.data.SecureAccountStore.save(SourceType.PICACG, email.trim(), password)
    }

    override suspend fun register(email: String, password: String, name: String, gender: String) {
        // 哔咔注册：昵称 + 邮箱 + 密码 + 性别 + 生日（成年校验，生日固定填 18 年前）
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.YEAR, -18)
        val birthday = "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        PicaClient.safeCall {
            PicaClient.api.register(
                com.pika.network.RegisterPayload(
                    email = email,
                    password = password,
                    name = name,
                    gender = gender,
                    birthday = birthday,
                )
            )
        }
        // 注册成功即自动登录，复用登录逻辑保存 token
        login(email, password)
    }

    override suspend fun logout() {
        SourcePrefs.current().clearPicaLogin()
    }

    // 分类接口结果内存缓存（几乎不变的数据，categories/tags 两个入口共享）
    private var categoriesCache: com.pika.network.CategoriesResponse? = null
    private var categoriesAt: Long = 0L
    private val categoriesMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun cachedCategories(): com.pika.network.CategoriesResponse =
        categoriesMutex.withLock {
            val now = System.currentTimeMillis()
            val cache = categoriesCache
            if (cache != null && now - categoriesAt < 10 * 60_000L) {
                cache
            } else {
                PicaClient.safeCall { PicaClient.api.categories() }.also {
                    categoriesCache = it
                    categoriesAt = now
                }
            }
        }

    override suspend fun categories(): List<ComicCategory> {
        val data = cachedCategories()
        return data.categories
            .filter { it.active != false }
            .map { it.toComicCategory() }
    }

    /** 官方标签词表：分类接口返回的非 Web 分类名（Web 分类为广告/外链入口，不可作标签筛选） */
    override suspend fun tags(): List<String> {
        val data = cachedCategories()
        return data.categories
            .filter { it.active != false && it.isWeb != true }
            .map { it.title }
    }

    override suspend fun browse(
        page: Int,
        category: String?,
        sort: ComicSort,
        author: String?,
        tag: String?,
    ): PageResult<ComicSummary> {
        val data = PicaClient.safeCall {
            PicaClient.api.comics(
                comicsQuery(
                    page = page,
                    category = category,
                    sort = sort.toPicaSort(),
                    author = author,
                    tag = tag,
                )
            )
        }
        return PageResult(
            items = data.comics.docs.map { it.toSummary() },
            page = data.comics.page,
            pages = data.comics.pages.coerceAtLeast(1),
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
        val data = PicaClient.safeCall {
            PicaClient.api.search(
                page = page,
                body = SearchPayload(
                    keyword = keyword,
                    sort = sort.toPicaSort().name.lowercase(),
                    categories = categories,
                    tags = tags,
                    author = author,
                    chineseTeam = chineseTeam,
                    uploader = uploader,
                    finish = finished,
                ),
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

    /**
     * 章节列表：首页拿到总页数后并发拉取剩余页（Semaphore 限 3 路，避免触发限流）。
     * 并发失败的页退回串行重试（safeCall 自带切域名重试），保证不丢章。
     */
    override suspend fun chapters(id: String): List<ComicChapter> = coroutineScope {
        val first = PicaClient.safeCall { PicaClient.api.chapters(id, page = 1) }
        val docs = mutableListOf<com.pika.network.Chapter>()
        docs += first.eps.docs
        val totalPages = first.eps.pages
        if (totalPages > 1) {
            val sem = Semaphore(3)
            val byPage = (2..totalPages).map { p ->
                async {
                    sem.withPermit {
                        p to runCatching {
                            PicaClient.safeCall { PicaClient.api.chapters(id, p) }
                        }.getOrNull()?.eps?.docs
                    }
                }
            }.awaitAll()
            for ((p, docsP) in byPage.sortedBy { it.first }) {
                if (docsP != null) docs += docsP
            }
            // 并发失败的页退回串行重试
            val okPages = byPage.filter { it.second != null }.map { it.first }.toSet()
            for (p in (2..totalPages).filter { it !in okPages }) {
                docs += PicaClient.safeCall { PicaClient.api.chapters(id, p) }.eps.docs
            }
        }
        docs.mapIndexed { index, c ->
            com.pika.core.model.ComicChapter(
                id = c.uid.ifBlank { c.id },
                title = c.title,
                order = c.order.takeIf { it > 0 } ?: (index + 1),
            )
        }
    }

    /**
     * 章节图片：同章节列表，按页号并发拉取后按页序拼接，保证图片顺序不变。
     */
    override suspend fun chapterPages(comicId: String, order: Int): List<ComicPage> = coroutineScope {
        val first = PicaClient.safeCall { PicaClient.api.chapterImages(comicId, order, page = 1) }
        val totalPages = first.pages.pages
        // 按页号缓存，拼接时严格按页序，保证 index 连续且顺序正确
        val byPage = HashMap<Int, com.pika.network.FetchChapterImagesResponse>()
        byPage[1] = first
        if (totalPages > 1) {
            val sem = Semaphore(3)
            (2..totalPages).map { p ->
                async {
                    sem.withPermit {
                        p to runCatching {
                            PicaClient.safeCall { PicaClient.api.chapterImages(comicId, order, p) }
                        }.getOrNull()
                    }
                }
            }.awaitAll().forEach { (p, resp) -> if (resp != null) byPage[p] = resp }
            // 并发失败的页退回串行重试
            for (p in (2..totalPages).filter { !byPage.containsKey(it) }) {
                byPage[p] = PicaClient.safeCall { PicaClient.api.chapterImages(comicId, order, p) }
            }
        }
        val out = mutableListOf<ComicPage>()
        for (p in 1..totalPages) {
            val data = byPage[p] ?: continue
            data.pages.docs.forEach { doc ->
                doc.media?.let { m ->
                    out += ComicPage(index = out.size, imageUrl = m.directUrl)
                }
            }
        }
        out
    }

    override suspend fun favourite(comicId: String, add: Boolean): Boolean {
        val resp = PicaClient.safeCall { PicaClient.api.favorite(comicId) }
        LogStore.log("PicacgSource", "D", "favourite response: action=\"${resp.action}\"")
        // 哔咔端点为切换型：以响应 action 文案回写真实状态，避免本地状态与服务端失步后越点越错
        return resp.action.contains("收藏") && !resp.action.contains("取消")
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

    override suspend fun rank(type: String): List<ComicSummary> {
        val data = PicaClient.safeCall {
            PicaClient.api.leaderboard(
                com.pika.network.rankQuery(
                    when (type) {
                        "H24" -> com.pika.core.pica.ComicRankType.H24
                        "D7" -> com.pika.core.pica.ComicRankType.D7
                        else -> com.pika.core.pica.ComicRankType.D30
                    }
                )
            )
        }
        return data.comics.map { it.toSummary() }
    }

    override suspend fun randomComics(): List<ComicSummary> {
        val data = PicaClient.safeCall { PicaClient.api.random() }
        return data.comics.map { it.toSummary() }
    }

    override suspend fun recommendations(id: String): List<ComicSummary> {
        val data = PicaClient.safeCall { PicaClient.api.recommendation(id) }
        return data.comics.map { it.toSummary() }
    }

    override suspend fun profile(): com.pika.core.model.ComicUser {
        val data = PicaClient.safeCall { PicaClient.api.profile() }
        return data.user.toComicUser()
    }

    // ---------- 评论 ----------

    override suspend fun comments(comicId: String, page: Int): PageResult<com.pika.core.model.ComicComment> {
        val data = PicaClient.safeCall { PicaClient.api.comments(comicId, page) }
        return PageResult(
            items = data.comments.docs.map { it.toComicComment() },
            page = data.comments.page,
            pages = data.comments.pages.coerceAtLeast(1),
        )
    }

    override suspend fun sendComment(comicId: String, content: String) {
        PicaClient.safeCall {
            PicaClient.api.sendComment(
                comicId,
                com.pika.network.SendCommentPayload(content = content),
            )
        }
    }

    override suspend fun replyComment(commentId: String, content: String) {
        PicaClient.safeCall {
            PicaClient.api.replyComment(
                commentId,
                com.pika.network.SendCommentPayload(content = content),
            )
        }
    }

    override suspend fun commentChildren(
        commentId: String,
        page: Int,
    ): PageResult<com.pika.core.model.ComicComment> {
        val data = PicaClient.safeCall { PicaClient.api.commentChildren(commentId, page) }
        return PageResult(
            items = data.comments.docs.map { it.toComicComment() },
            page = data.comments.page,
            pages = data.comments.pages.coerceAtLeast(1),
        )
    }

    override suspend fun myComments(page: Int): PageResult<com.pika.core.model.MyComicComment> {
        val data = PicaClient.safeCall { PicaClient.api.myComments(page) }
        return PageResult(
            items = data.comments.docs.map { it.toMyComicComment() },
            page = data.comments.page,
            pages = data.comments.pages.coerceAtLeast(1),
        )
    }

    // ---------- 账号管理 ----------

    override suspend fun forgotPassword(email: String) {
        PicaClient.safeCall {
            PicaClient.api.forgotPassword(
                com.pika.network.ForgotPasswordPayload(email = email),
            )
        }
    }

    override suspend fun updateSlogan(slogan: String) {
        PicaClient.safeCall {
            PicaClient.api.updateProfile(
                com.pika.network.UpdateProfilePayload(slogan = slogan),
            )
        }
    }

    override suspend fun updatePassword(oldPassword: String, newPassword: String) {
        PicaClient.safeCall {
            PicaClient.api.updatePassword(
                com.pika.network.UpdatePasswordPayload(
                    oldPassword = oldPassword,
                    newPassword = newPassword,
                ),
            )
        }
    }

    override suspend fun updateAvatar(base64: String) {
        PicaClient.safeCall {
            // 按真实图片格式标注 mime，避免 PNG/WebP 头像被错误标成 jpeg
            val mime = when {
                base64.startsWith("iVBOR") -> "image/png"
                base64.startsWith("UklGR") -> "image/webp"
                base64.startsWith("R0lGO") -> "image/gif"
                else -> "image/jpeg"
            }
            PicaClient.api.updateAvatar(
                com.pika.network.UpdateAvatarPayload(avatar = "data:$mime;base64,$base64"),
            )
        }
    }

    override suspend fun updateTitle(title: String) {
        val me = profile()
        if (me.id.isBlank()) throw com.pika.network.PicaException("获取用户信息失败")
        PicaClient.safeCall {
            PicaClient.api.updateTitle(me.id, com.pika.network.UpdateTitlePayload(title = title))
        }
    }
}

// ---------- 映射 ----------

private fun ComicSort.toPicaSort(): com.pika.core.pica.ComicSortType = when (this) {
    ComicSort.DD -> com.pika.core.pica.ComicSortType.DD
    ComicSort.DA -> com.pika.core.pica.ComicSortType.DA
    ComicSort.LD -> com.pika.core.pica.ComicSortType.LD
    ComicSort.VD -> com.pika.core.pica.ComicSortType.VD
}

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
    tags = tags,
    updatedAt = updatedAt,
)

private fun Comic.toDetail() = ComicDetail(
    id = id,
    title = title,
    author = author?.takeIf { it.isNotBlank() } ?: creator?.name ?: "",
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
    updatedAt = updatedAt,
    createdAt = createdAt,
)

// ---------- 评论/用户映射 ----------

private fun com.pika.network.RecommendComic.toSummary() = ComicSummary(
    id = id,
    title = title,
    author = author,
    coverUrl = thumb?.directUrl,
    finished = finished,
    totalLikes = likesCount.toLong(),
)

private fun com.pika.network.Creator.toComicUser() = com.pika.core.model.ComicUser(
    id = id,
    name = name,
    avatarUrl = avatar?.directUrl,
    level = level,
    exp = exp,
    title = title,
    slogan = slogan ?: "",
)

private fun com.pika.network.User.toComicUser() = com.pika.core.model.ComicUser(
    id = id,
    name = name,
    avatarUrl = avatar?.directUrl,
    level = level,
    exp = exp,
    title = title,
    slogan = slogan,
    email = email,
    gender = gender,
    birthday = birthday,
    characters = characters,
    createdAt = createdAt,
)

private fun com.pika.network.Comment.toComicComment() = com.pika.core.model.ComicComment(
    id = uid,
    content = content,
    user = user?.toComicUser(),
    createdAt = createdAt,
    likesCount = likesCount,
    isLiked = isLiked,
    commentsCount = commentsCount,
    isTop = isTop,
)

private fun com.pika.network.PersonalComment.toMyComicComment() = com.pika.core.model.MyComicComment(
    id = uid,
    content = content,
    comicId = comic?.id ?: "",
    comicTitle = comic?.title ?: "",
    createdAt = createdAt,
    likesCount = likesCount,
)