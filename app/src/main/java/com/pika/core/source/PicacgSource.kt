package com.pika.core.source

import com.pika.core.log.LogStore
import com.pika.core.model.AuthorMatcher
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
import com.pika.core.pica.ComicRankType
import com.pika.core.pica.ComicSortType
import com.pika.core.runCatchingCancellable
import com.pika.data.SecureAccountStore
import com.pika.data.SourcePrefs
import com.pika.network.CategoriesResponse
import com.pika.network.Category
import com.pika.network.Chapter
import com.pika.network.Comic
import com.pika.network.Comment
import com.pika.network.Creator
import com.pika.network.Doc
import com.pika.network.FetchChapterImagesResponse
import com.pika.network.ForgotPasswordPayload
import com.pika.network.LoginPayload
import com.pika.network.PersonalComment
import com.pika.network.PicaClient
import com.pika.network.PicaException
import com.pika.network.RecommendComic
import com.pika.network.RegisterPayload
import com.pika.network.SearchPayload
import com.pika.network.SendCommentPayload
import com.pika.network.UpdateAvatarPayload
import com.pika.network.UpdatePasswordPayload
import com.pika.network.UpdateProfilePayload
import com.pika.network.UpdateTitlePayload
import com.pika.network.User
import com.pika.network.comicsQuery
import com.pika.network.favouriteQuery
import com.pika.network.rankQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

/** 批量分页拉取的请求节拍（与搜索批量路径一致的 250ms） */
private const val PICA_REQUEST_INTERVAL_MS = 250L

/** 作者片段检索：整页被客户端过滤为空时，最多继续顺延的页数（避免用户看到整页空白） */
private const val AUTHOR_EMPTY_PAGE_LOOKAHEAD = 3

/**
 * 哔咔源：包装 PicaClient，映射为统一模型。
 */
class PicacgSource : Source {

    override val type: SourceType = SourceType.PICACG

    override val isLoggedIn: Boolean
        get() = !SourcePrefs.current().picaToken.isNullOrEmpty()

    /**
     * 已确认需要走 `a` 参数兜底的作者查询。
     *
     * 仅缓存「关键词搜索整页为空、但 `a` 精确匹配有结果」的片段：换一个作者仍优先走关键词，
     * 避免一次兜底把整个源的后续查询都降级成精确匹配。上限 64 条，防止无限增长。
     */
    private val authorBrowseQueries = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override suspend fun login(email: String, password: String) {
        val data = PicaClient.safeCall {
            PicaClient.api.login(LoginPayload(email = email, password = password))
        }
        // 显式登录成功即恢复 401 静默重登能力（清除用户登出时置的标志，见 SourceManager.S2）
        SourceManager.onLoginSuccess()
        SourcePrefs.current().setPicaLogin(token = data.token, email = email)
        // 账号变化可能带来分类权限差异，清一次内存缓存
        categoriesMutex.withLock { categoriesCache = null; categoriesAt = 0L }
        // 保存账号凭据，供登出后会话失效时静默重登/一键填充
        // （是否落盘由用户偏好控制，见 SecureAccountStore.saveEnabled）
        SecureAccountStore.save(SourceType.PICACG, email.trim(), password)
    }

    override suspend fun register(email: String, password: String, name: String, gender: String) {
        // 哔咔注册：昵称 + 邮箱 + 密码 + 性别 + 生日（成年校验）
        // 生日取「18 岁零 1 天」而非恰好 18 岁当天：边界值容易被服务端成年校验拒绝
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.YEAR, -18)
        cal.add(java.util.Calendar.DAY_OF_MONTH, -1)
        val birthday = "%04d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        PicaClient.safeCall {
            PicaClient.api.register(
                RegisterPayload(
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
        // 分类可能随账号权限差异而不同，登出时清掉内存缓存，避免换账号后仍显示旧分类
        categoriesMutex.withLock { categoriesCache = null; categoriesAt = 0L }
    }

    // 分类接口结果内存缓存（几乎不变的数据，categories/tags 两个入口共享）
    private var categoriesCache: CategoriesResponse? = null
    private var categoriesAt: Long = 0L
    private val categoriesMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun cachedCategories(): CategoriesResponse =
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

    /**
     * 作者片段检索：关键词搜索 + 客户端「author 字段包含」校验。
     *
     * 走搜索接口而不是 `a` 参数，是因为关注流已实测「以作者名为关键词可匹配该作者作品」；
     * `a` 是否为子串匹配尚未验证。关键词会命中标题/标签，因此必须二次过滤。
     * 整页过滤为空时顺延下一页（最多 3 页），避免分页出现整页空白。
     */
    override suspend fun authorWorks(
        query: String,
        page: Int,
        sort: ComicSort,
    ): PageResult<ComicSummary> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return PageResult(emptyList(), page = page, pages = 1)

        // 该片段已确认关键词路径不可用：直接走 `a` 参数，保证翻页不会从第 2 页起变空
        if (authorBrowseQueries.contains(keyword)) {
            return browseAuthorPage(keyword, page, sort)
        }

        var serverPage = page
        var lookahead = 0
        var pages = 1
        val items = mutableListOf<ComicSummary>()
        while (true) {
            val data = PicaClient.safeCall {
                PicaClient.api.search(
                    page = serverPage,
                    body = SearchPayload(
                        keyword = keyword,
                        sort = sort.toPicaSort().name.lowercase(),
                    ),
                )
            }
            pages = data.comics.pages.coerceAtLeast(1)
            items += data.comics.docs
                .map { it.toDoc().toSummary() }
                .filter { AuthorMatcher.matches(it.author, keyword) }

            if (items.isNotEmpty() || serverPage >= pages || lookahead >= AUTHOR_EMPTY_PAGE_LOOKAHEAD) break
            serverPage++
            lookahead++
        }
        // 搜索无结果时用旧的 `a` 参数兜底：若服务端 `a` 支持子串匹配，可以救回
        // 「关键词只命中标题、作者字段则为空」这一灰区；失败/仍为空则如实返回空态
        if (items.isEmpty() && page == 1) {
            val fallback = runCatchingCancellable {
                browseAuthorPage(keyword, page = 1, sort = sort)
            }.getOrNull()
            if (fallback != null && fallback.items.isNotEmpty()) {
                if (authorBrowseQueries.size >= 64) authorBrowseQueries.clear()
                authorBrowseQueries.add(keyword)
                return fallback
            }
        }
        return PageResult(
            items = items.distinctBy { it.id },
            // 返回实际生效页（可能因空页顺延而后移），UI 页码据此对齐，避免跨页重复
            page = serverPage,
            pages = pages,
        )
    }

    /** `a` 参数兜底：仍做一次作者字段包含校验，防止服务端把参数当模糊查询而混入无关作品 */
    private suspend fun browseAuthorPage(
        keyword: String,
        page: Int,
        sort: ComicSort,
    ): PageResult<ComicSummary> {
        val result = browse(page = page, category = null, sort = sort, author = keyword, tag = null)
        return PageResult(
            items = result.items
                .filter { AuthorMatcher.matches(it.author, keyword) }
                .distinctBy { it.id },
            page = result.page.coerceAtLeast(1),
            pages = result.pages.coerceAtLeast(1),
        )
    }

    override suspend fun comicDetail(id: String): ComicDetail {
        val data = PicaClient.safeCall { PicaClient.api.comic(id) }
        return data.comic.toDetail()
    }

    /**
     * 章节列表：首页拿到总页数后，剩余页交给 [fetchPagesInOrder]（并发 3 路、
     * 冷却检查、250ms 节拍、失败页串行补拉）。并发度 3 是原实现已实测的取值，**不改**。
     */
    override suspend fun chapters(id: String): List<ComicChapter> {
        val first = PicaClient.safeCall { PicaClient.api.chapters(id, page = 1) }
        val restDocs = fetchPagesInOrder(
            totalPages = first.eps.pages,
            isCoolingDown = ::picaCooldownActive,
            awaitCooldown = ::awaitCooldown,
        ) { p -> PicaClient.safeCall { PicaClient.api.chapters(id, p) }.eps.docs }
        val docs = first.eps.docs + restDocs.flatten()
        return docs.mapIndexed { index, c ->
            ComicChapter(
                id = c.uid.ifBlank { c.id },
                title = c.title,
                order = c.order.takeIf { it > 0 } ?: (index + 1),
            )
        }
    }

    /** 服务端限流冷却中（>0 表示仍在冷却） */
    private fun picaCooldownActive(): Boolean = PicaClient.rateLimitRemaining() > 0

    /** 等待限流冷却结束：补拉关键页时宁可等待/显式失败，也不静默丢页 */
    private suspend fun awaitCooldown() {
        while (true) {
            val remain = PicaClient.rateLimitRemaining()
            if (remain <= 0) return
            delay(remain + 50)
        }
    }

    /**
     * 章节图片：与 [chapters] 共用 [fetchPagesInOrder] 的并发/冷却/补拉策略，
     * 按页号还原顺序后拼接，保证图片 index 连续且顺序不变。
     */
    override suspend fun chapterPages(comicId: String, order: Int): List<ComicPage> {
        val first = PicaClient.safeCall { PicaClient.api.chapterImages(comicId, order, page = 1) }
        val totalPages = first.pages.pages
        val rest = fetchPagesInOrder(
            totalPages = totalPages,
            isCoolingDown = ::picaCooldownActive,
            awaitCooldown = ::awaitCooldown,
        ) { p -> PicaClient.safeCall { PicaClient.api.chapterImages(comicId, order, p) } }
        // 按页号还原顺序：首页 + 第 2 页起的并发结果
        val byPage = buildMap {
            put(1, first)
            rest.forEachIndexed { i, response -> put(i + 2, response) }
        }
        val out = mutableListOf<ComicPage>()
        for (p in 1..totalPages) {
            // 拼装前缺页即显式失败：绝不返回截断列表（消费方会把缺页当成真实页数固化）
            val data = byPage[p] ?: throw PicaException("章节第 $p 页拉取失败")
            data.pages.docs.forEach { doc ->
                doc.media?.let { m ->
                    out += ComicPage(index = out.size, imageUrl = m.directUrl)
                }
            }
        }
        return out
    }

    override suspend fun favourite(comicId: String, add: Boolean): Boolean {
        val resp = PicaClient.safeCall { PicaClient.api.favorite(comicId) }
        LogStore.log("PicacgSource", "D", "favourite response: action=\"${resp.action}\"")
        val now = parseFavouriteAction(resp.action)
            ?: throw PicaException("收藏结果无法识别（action=\"${resp.action}\"）")
        if (now == add) return now
        // 端点为切换型：本地与服务端失步时（他端已收藏/取消），一次切换会切到与 add 相反的状态。
        // 再切一次纠正，保证返回值恒等于调用方请求的 add。
        val corrected = PicaClient.safeCall { PicaClient.api.favorite(comicId) }
        LogStore.log("PicacgSource", "D", "favourite corrected: action=\"${corrected.action}\"")
        return parseFavouriteAction(corrected.action)
            ?: throw PicaException("收藏结果无法识别（action=\"${corrected.action}\"）")
    }

    /**
     * 解析切换型收藏接口返回的 action，得到操作后的真实状态。
     *
     * 实测服务端返回的是英文机器码 `favourite` / `un_favourite`，而 v1.5.44 起按中文文案
     * `contains("收藏")` 判断——对 `favourite` 恒为假，导致收藏后心形不点亮（表现为"收藏无效"）。
     * 此处同时兼容机器码与中文文案：必须先判否定（`un_` 前缀 / "取消"），否则
     * `un_favourite` 会因含 `favourite` 被判成已收藏。
     *
     * @return 已收藏 true / 未收藏 false / 文案无法识别时 null（交由调用方报错，不猜）
     */
    private fun parseFavouriteAction(action: String): Boolean? {
        val a = action.trim().lowercase()
        if (a.isEmpty()) return null
        if (a.startsWith("un_") || a.startsWith("un-") || a.contains("取消")) return false
        if (a.contains("favourite") || a.contains("favorite") || a.contains("收藏")) return true
        return null
    }

    override suspend fun favourites(page: Int): PageResult<ComicSummary> {
        val data = PicaClient.safeCall {
            PicaClient.api.favourites(
                favouriteQuery(page, ComicSortType.DD),
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
                rankQuery(
                    when (type) {
                        "H24" -> ComicRankType.H24
                        "D7" -> ComicRankType.D7
                        else -> ComicRankType.D30
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

    override suspend fun profile(): ComicUser {
        val data = PicaClient.safeCall { PicaClient.api.profile() }
        // user 字段缺失（服务端返回结构抖动）时给出可读错误，而不是崩溃
        val user = data.user ?: throw PicaException("获取用户信息失败：响应缺少 user 字段")
        return user.toComicUser()
    }

    // ---------- 评论 ----------

    override suspend fun comments(comicId: String, page: Int): PageResult<ComicComment> {
        val data = PicaClient.safeCall { PicaClient.api.comments(comicId, page) }
        return PageResult(
            items = data.comments.docs.map { it.toComicComment() },
            page = data.comments.page,
            pages = data.comments.pages.coerceAtLeast(1),
        )
    }

    override suspend fun sendComment(comicId: String, content: String) {
        // 纯确认型：只校验业务 code，不要求返回 data（否则服务端省略 data 时
        // 已成功的评论会被报成失败，用户会重复提交）
        PicaClient.safeCallUnit {
            PicaClient.api.sendComment(
                comicId,
                SendCommentPayload(content = content),
            )
        }
    }

    override suspend fun replyComment(commentId: String, content: String) {
        PicaClient.safeCallUnit {
            PicaClient.api.replyComment(
                commentId,
                SendCommentPayload(content = content),
            )
        }
    }

    override suspend fun commentChildren(
        commentId: String,
        page: Int,
    ): PageResult<ComicComment> {
        val data = PicaClient.safeCall { PicaClient.api.commentChildren(commentId, page) }
        return PageResult(
            items = data.comments.docs.map { it.toComicComment() },
            page = data.comments.page,
            pages = data.comments.pages.coerceAtLeast(1),
        )
    }

    override suspend fun myComments(page: Int): PageResult<MyComicComment> {
        val data = PicaClient.safeCall { PicaClient.api.myComments(page) }
        return PageResult(
            items = data.comments.docs.map { it.toMyComicComment() },
            page = data.comments.page,
            pages = data.comments.pages.coerceAtLeast(1),
        )
    }

    /** 点赞/取消点赞（服务端 toggle 型）：返回点赞后的状态 */
    override suspend fun likeComment(comicId: String, commentId: String): Boolean {
        val data = PicaClient.safeCall { PicaClient.api.likeComment(comicId, commentId) }
        return data.action.equals("like", ignoreCase = true)
    }

    // ---------- 账号管理 ----------

    /** 纯确认型接口（无返回体）：只校验业务 code */
    override suspend fun forgotPassword(email: String) {
        PicaClient.safeCallUnit {
            PicaClient.api.forgotPassword(
                ForgotPasswordPayload(email = email),
            )
        }
    }

    override suspend fun updateSlogan(slogan: String) {
        PicaClient.safeCallUnit {
            PicaClient.api.updateProfile(
                UpdateProfilePayload(slogan = slogan),
            )
        }
    }

    override suspend fun updatePassword(oldPassword: String, newPassword: String) {
        PicaClient.safeCallUnit {
            PicaClient.api.updatePassword(
                UpdatePasswordPayload(
                    oldPassword = oldPassword,
                    newPassword = newPassword,
                ),
            )
        }
    }

    override suspend fun updateAvatar(base64: String) {
        PicaClient.safeCallUnit {
            // 按真实图片格式标注 mime，避免 PNG/WebP 头像被错误标成 jpeg
            val mime = when {
                base64.startsWith("iVBOR") -> "image/png"
                base64.startsWith("UklGR") -> "image/webp"
                base64.startsWith("R0lGO") -> "image/gif"
                else -> "image/jpeg"
            }
            PicaClient.api.updateAvatar(
                UpdateAvatarPayload(avatar = "data:$mime;base64,$base64"),
            )
        }
    }

    override suspend fun updateTitle(title: String) {
        val me = profile()
        if (me.id.isBlank()) throw PicaException("获取用户信息失败")
        PicaClient.safeCallUnit {
            PicaClient.api.updateTitle(me.id, UpdateTitlePayload(title = title))
        }
    }
}

// ---------- 映射 ----------

private fun ComicSort.toPicaSort(): ComicSortType = when (this) {
    ComicSort.DD -> ComicSortType.DD
    ComicSort.DA -> ComicSortType.DA
    ComicSort.LD -> ComicSortType.LD
    ComicSort.VD -> ComicSortType.VD
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
    isFavourite = isFavourite,
)

// ---------- 评论/用户映射 ----------

private fun RecommendComic.toSummary() = ComicSummary(
    id = id,
    title = title,
    author = author,
    coverUrl = thumb?.directUrl,
    finished = finished,
    totalLikes = likesCount.toLong(),
)

private fun Creator.toComicUser() = ComicUser(
    id = id,
    name = name,
    avatarUrl = avatar?.directUrl,
    level = level,
    exp = exp,
    title = title,
    slogan = slogan ?: "",
)

private fun User.toComicUser() = ComicUser(
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

private fun Comment.toComicComment() = ComicComment(
    id = uid,
    content = content,
    user = user?.toComicUser(),
    createdAt = createdAt,
    likesCount = likesCount,
    isLiked = isLiked,
    commentsCount = commentsCount,
    isTop = isTop,
)

private fun PersonalComment.toMyComicComment() = MyComicComment(
    id = uid,
    content = content,
    comicId = comic?.id ?: "",
    comicTitle = comic?.title ?: "",
    createdAt = createdAt,
    likesCount = likesCount,
)