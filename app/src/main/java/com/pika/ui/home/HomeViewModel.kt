package com.pika.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.sortedByComicSort
import com.pika.core.source.Source
import com.pika.core.source.SourceManager
import com.pika.data.AuthorFavourites
import com.pika.data.FollowSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 关注来源类型 */
private enum class FollowTargetType { AUTHOR, KEYWORD }

/** 排行榜数据过期阈值：回前台时超过该时长则静默重拉（覆盖进程被后台保留的"温启动"场景） */
private const val RANK_REFRESH_TTL_MS = 10 * 60 * 1000L

/** 一个关注来源：作者 / 组合关键词（空格连接）+ 可选标签 */
private data class FollowTarget(
    val key: String,
    val type: FollowTargetType,
    val name: String,
    val tag: String? = null,
)

/**
 * 相邻关注来源之间的请求间隔。
 *
 * 哔咔服务端限流约 2 次/秒，连续无间隔请求会在第 2~3 个来源触发全局 60 秒冷却，
 * 冷却期内后续所有来源必然失败且被静默丢弃 → 一次刷新只剩前一两个来源的内容。
 * 关注来源越多越明显，这是"刷新后内容忽多忽少"的主因。
 */
private const val TARGET_REQUEST_INTERVAL_MS = 600L

/** 一次关注流拉取的结果 */
private data class FollowFetchResult(
    val items: List<ComicSummary>,
    /** 本轮未成功拉取的来源数量（含被限流打断而从未开始的） */
    val failedCount: Int,
    /** > 0 表示本轮被服务端限流冷却打断，值为冷却剩余秒数 */
    val rateLimitSeconds: Long,
)

/**
 * 首页数据聚合：排行榜(H24/D7/D30) + 关注信息流。
 * 关注流 = 所有关注来源（收藏作者/关键词/分类标签）的最新作品合并，
 * 按更新时间由近至远排序、按 id 去重，滚动加载（每个来源逐页拉取）。
 */
class HomeViewModel : ViewModel() {

    private val _followFeed = MutableStateFlow<List<ComicSummary>>(emptyList())
    val followFeed: StateFlow<List<ComicSummary>> = _followFeed.asStateFlow()

    private val _followEndReached = MutableStateFlow(false)
    val followEndReached: StateFlow<Boolean> = _followEndReached.asStateFlow()

    private val _followLoading = MutableStateFlow(false)
    val followLoading: StateFlow<Boolean> = _followLoading.asStateFlow()

    private val _followEmptyHint = MutableStateFlow<String?>(null)
    val followEmptyHint: StateFlow<String?> = _followEmptyHint.asStateFlow()

    private val _followError = MutableStateFlow<String?>(null)
    val followError: StateFlow<String?> = _followError.asStateFlow()

    /** 关注流每次刷新完成 +1（UI 据此回到列表顶部，从最新开始展示） */
    private val _refreshTick = MutableStateFlow(0)
    val refreshTick: StateFlow<Int> = _refreshTick.asStateFlow()

    /** 自动刷新节流：ON_RESUME 触发时距上次刷新不足阈值则跳过（下拉刷新不受影响） */
    private var lastAutoRefreshAt = System.currentTimeMillis()

    fun refreshOnResume() {
        val now = System.currentTimeMillis()
        // 排行榜 TTL 刷新：回前台时数据过期则静默重拉当前榜
        // （延迟 2 秒启动：关注流刷新也在本方法触发，错峰避免两个来源的请求叠加触发限流）
        if (rankLoadedAt > 0 && now - rankLoadedAt >= RANK_REFRESH_TTL_MS) {
            loadRank(_rankType.value, force = true, startDelayMs = 2_000)
        }
        if (now - lastAutoRefreshAt < 30_000) return
        lastAutoRefreshAt = now
        refresh()
    }

    private val _rankComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val rankComics: StateFlow<List<ComicSummary>> = _rankComics.asStateFlow()

    private val _rankType = MutableStateFlow("H24")
    val rankType: StateFlow<String> = _rankType.asStateFlow()

    private val _rankLoading = MutableStateFlow(false)
    val rankLoading: StateFlow<Boolean> = _rankLoading.asStateFlow()

    private val _rankError = MutableStateFlow<String?>(null)
    val rankError: StateFlow<String?> = _rankError.asStateFlow()

    /** 排行榜最近一次成功加载时间（TTL 刷新判断用） */
    private var rankLoadedAt = 0L

    /** 排行榜刷新完成计数：UI 据此回到列表顶部 */
    private val _rankRefreshTick = MutableStateFlow(0)
    val rankRefreshTick: StateFlow<Int> = _rankRefreshTick.asStateFlow()

    private val _randomComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val randomComics: StateFlow<List<ComicSummary>> = _randomComics.asStateFlow()

    private val _randomLoading = MutableStateFlow(false)
    val randomLoading: StateFlow<Boolean> = _randomLoading.asStateFlow()

    private var randomLoaded = false

    /** 各关注来源当前已加载到的页数（key -> page） */
    private var targetPages = mutableMapOf<String, Int>()

    /** 各关注来源是否已到末页 */
    private var targetEnded = mutableMapOf<String, Boolean>()

    private var targets: List<FollowTarget> = emptyList()

    private var followLoadingJob: kotlinx.coroutines.Job? = null

    /** 各 Tab 滚动位置恢复 */
    private var _savedFollowIndex = 0
    val savedFollowIndex: Int get() = _savedFollowIndex

    private var _savedRankIndex = 0
    val savedRankIndex: Int get() = _savedRankIndex

    private var _savedRandomIndex = 0
    val savedRandomIndex: Int get() = _savedRandomIndex

    private val _isScrollStateRestored = MutableStateFlow(false)
    val isScrollStateRestored: StateFlow<Boolean> = _isScrollStateRestored

    fun saveScrollState(tab: Int, index: Int) {
        when (tab) {
            0 -> _savedFollowIndex = index
            1 -> _savedRankIndex = index
            2 -> _savedRandomIndex = index
        }
    }

    fun markScrollStateRestored() {
        _isScrollStateRestored.value = true
    }

    init {
        // 冷启动：先展示上次成功刷新的缓存，后台静默刷新替换
        _followFeed.value = com.pika.data.FollowFeedCache.load()
    }

    /**
     * 加载指定排行榜（日 H24 / 周 D7 / 月 D30）；切换类型时清空旧榜，避免旧数据残留。
     * force = true 时静默重拉当前榜：期间保留旧数据展示，成功后替换并触发回顶；
     * 失败时保留旧数据、仅记录错误（UI 显示顶部横幅），不清空列表。
     * startDelayMs > 0 时延迟启动：用于回前台场景，避开与关注流刷新的请求撞车
     * （两者同时开跑会顶到哔咔约 2 次/秒的限流线，排行榜先撞还会触发 60 秒全局冷却拖垮关注流）。
     */
    fun loadRank(type: String, force: Boolean = false, startDelayMs: Long = 0) {
        if (!force && _rankType.value == type && _rankComics.value.isNotEmpty() && _rankError.value == null) return
        _rankType.value = type
        if (!force && _rankComics.value.isNotEmpty()) _rankComics.value = emptyList()
        _rankLoading.value = true
        _rankError.value = null
        viewModelScope.launch {
            try {
                if (startDelayMs > 0) kotlinx.coroutines.delay(startDelayMs)
                _rankComics.value = SourceManager.current().rank(type)
                rankLoadedAt = System.currentTimeMillis()
                // 刷新完成（换榜/强刷均适用）：通知 UI 回到顶部，从新版第 1 名开始展示
                _rankRefreshTick.value++
            } catch (e: Exception) {
                if (!force) _rankComics.value = emptyList()
                _rankError.value = e.message ?: "加载排行榜失败"
            } finally {
                _rankLoading.value = false
            }
        }
    }

    /** 首次进入关注 tab：构建关注来源列表 */
    fun ensureFollowTargets() {
        if (targets.isNotEmpty()) return
        rebuildTargets()
    }

    private fun rebuildTargets() {
        targets = buildList {
            AuthorFavourites.get().forEach { add(FollowTarget("a_${it.author}", FollowTargetType.AUTHOR, it.author)) }
            FollowSettings.items().forEach { item ->
                val name = item.keywords.joinToString(" ")
                add(FollowTarget("k_$name|${item.tag ?: ""}", FollowTargetType.KEYWORD, name, item.tag))
            }
        }
    }

    /** 下拉刷新：所有来源重新从第 1 页拉取，取前120条 */
    fun refresh() {
        rebuildTargets()
        if (targets.isEmpty()) {
            _followFeed.value = emptyList()
            _followEndReached.value = true
            _followEmptyHint.value = "还没有关注内容，去「我的 → 关注管理」添加作者或关键词关注"
            _followError.value = null
            com.pika.data.FollowFeedCache.clear()  // 清空关注时同步清缓存，避免回退显示旧数据
            return
        }
        _followEmptyHint.value = null
        _followLoading.value = true
        _followError.value = null
        followLoadingJob?.cancel()
        followLoadingJob = viewModelScope.launch {
            try {
                targetPages.clear()
                targetEnded.clear()
                // 只拉各来源第1页，合并后取前120条
                val result = fetchTargetPage(1)
                val totalFailed = result.failedCount
                // 全部来源都失败时保留上次内容：若不判断就合并，空结果会清空列表
                // 并置"关注的内容暂无更新"，把"请求全挂了"伪装成"确实没更新"，误导性极强。
                if (result.items.isNotEmpty() || totalFailed == 0) {
                    // 部分来源失败时改用合并（只增不减），避免一次抖动就让列表整体缩水
                    mergeIntoFeed(result.items, append = totalFailed > 0)
                    // 只有全部成功才回顶：部分成功时用户在原位继续看，新条目已按时间排入
                    if (totalFailed == 0) _refreshTick.value++
                    // 取前120条后永远不到底
                    if (_followFeed.value.size > 120) {
                        _followFeed.value = _followFeed.value.take(120)
                    }
                    // 成功刷新后持久化缓存（冷启动秒显）；保存失败不影响展示，吞掉异常
                    runCatching { com.pika.data.FollowFeedCache.save(_followFeed.value) }
                }
                _followEndReached.value = true
                // 部分来源失败要如实告知，不能静默——否则用户只会觉得"内容莫名其妙变少了"
                _followError.value = when {
                    result.rateLimitSeconds > 0 && result.items.isEmpty() ->
                        "请求过于频繁，${result.rateLimitSeconds} 秒后可再试（已保留上次内容）"
                    result.rateLimitSeconds > 0 ->
                        "请求过于频繁，${result.rateLimitSeconds} 秒后可再试（仅更新了部分内容）"
                    totalFailed > 0 && result.items.isEmpty() ->
                        "全部关注来源拉取失败，已保留上次内容"
                    totalFailed > 0 ->
                        "${totalFailed} 个关注来源暂时拉取失败，内容可能不完整"
                    else -> null
                }
            } catch (e: Exception) {
                // 取消是新一次刷新接管，不是失败：必须放行，否则会污染错误态并误报"刷新失败"
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 拉取失败：保留 init 已载入的缓存供展示，仅轻量提示，下拉刷新圈停止
                _followError.value = "刷新失败（${e.message ?: "网络错误"}），已展示上次缓存"
            } finally {
                // 已被新一次刷新接管（取消）时不要关闭加载态，否则会打断新刷新的转圈
                if (isActive) _followLoading.value = false
            }
        }
    }

    /** 首次进入随便看看 tab 时加载 */
    fun ensureRandomLoaded() {
        if (randomLoaded) return
        refreshRandom()
    }

    /** 下拉刷新：清空后加载全新随机推荐 */
    fun refreshRandom() {
        randomLoaded = true
        _randomLoading.value = true
        viewModelScope.launch {
            try {
                _randomComics.value = SourceManager.current().randomComics()
            } catch (e: Exception) {
                _randomComics.value = emptyList()
            } finally {
                _randomLoading.value = false
            }
        }
    }

    /**
     * 拉取所有来源的指定页。串行执行：哔咔服务端对高频并发请求会挂起（限速 ~2/s），
     * 多词拉取一次几十页，必须与其他来源错开；单词来源排前尽快出内容。
     * 已到末页的来源直接跳过。
     *
     * 每个来源之间强制间隔 [TARGET_REQUEST_INTERVAL_MS]，并在检测到服务端限流冷却时立即停止本轮，
     * 避免"打到限流 → 后续来源全灭且被静默吞掉"造成的刷新内容忽多忽少。
     */
    private suspend fun fetchTargetPage(page: Int): FollowFetchResult {
        val source = SourceManager.current()
        val result = mutableListOf<ComicSummary>()
        var failed = 0
        var rateLimitSeconds = 0L
        var requested = false
        val ordered = targets.sortedBy {
            if (it.type == FollowTargetType.KEYWORD && it.name.isNotBlank() && it.name.split(Regex("\\s+")).size > 1) 1 else 0
        }
        // 本轮预期要拉的来源（已到末页的跳过，不计入失败）
        val pending = ordered.filter { targetEnded[it.key] != true }
        var started = 0
        for (target in pending) {
            // 服务端已处于限流冷却：继续请求必然全部失败，还会不断续期冷却时间，直接停止本轮
            val cooldown = com.pika.network.PicaClient.rateLimitRemaining()
            if (cooldown > 0) {
                rateLimitSeconds = (cooldown + 999) / 1000
                break
            }
            // 来源之间强制间隔，避免自己把请求打进限流（此前无任何间隔，第 3 个来源起就开始丢）
            if (requested) kotlinx.coroutines.delay(TARGET_REQUEST_INTERVAL_MS)
            requested = true
            started++
            result += try {
                when (target.type) {
                    // 作者作品用全文搜索（关键字=作者名）拉取：浏览接口不带时间字段，
                    // 搜索接口按更新时间返回（实测作者名可完全匹配该作者全部作品）。
                    // 走 searchWithRetry：单词/作者来源此前没有任何重试，一次网络抖动就整源消失。
                    FollowTargetType.AUTHOR ->
                        searchWithRetry(source, target.name, page, emptyList())
                            .also { r ->
                                targetPages[target.key] = page
                                if (page >= r.pages) targetEnded[target.key] = true
                            }.items
                    FollowTargetType.KEYWORD ->
                        fetchKeywordPage(source, target, page)
                }
            } catch (e: Exception) {
                // 取消不属于失败，必须向上传播
                if (e is kotlinx.coroutines.CancellationException) throw e
                targetEnded[target.key] = true
                failed++
                emptyList()
            }
        }
        // 被限流打断而从未开始的来源同样算失败，用于向用户如实提示
        return FollowFetchResult(result, failed + (pending.size - started), rateLimitSeconds)
    }

    /**
     * 组合关键词拉取：服务端不支持空格分词，每个词分别全文搜索（标题/标签/简介）取 id 交集，
     * 保证"且"关系且不误杀（词可分别命中标题或标签）。
     * 关注项带标签时，每词搜索都带 categories=[标签]（服务端按 doc.categories 精确筛选），
     * 语义：关键词 AND 标签。
     */
    private suspend fun fetchKeywordPage(
        source: Source,
        target: FollowTarget,
        startPage: Int,
    ): List<ComicSummary> {
        val categories = if (target.tag == null) emptyList() else listOf(target.tag)
        val words = target.name.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        // 单词且无标签：直接取第 1 页（关注流语义：各来源最新作品）
        if (words.size <= 1 && target.tag == null) {
            val result = searchWithRetry(source, target.name, startPage, emptyList())
            targetPages[target.key] = startPage
            if (startPage >= result.pages) targetEnded[target.key] = true
            return result.items
        }
        // 多词 或 单词+标签：每词按第 1 页响应的 pages 拉取全部可返回页（服务端最多 50 页）
        // 取交集（带标签时每词已由服务端按标签筛选），一次拉完（无需滚动分页）；
        // Semaphore 控制并发避免限流（实测并发 4 安全）。
        if (startPage > 1) {
            targetEnded[target.key] = true
            return emptyList()
        }
        val semaphore = Semaphore(4)
        val wordPageCounts: List<Pair<String, Int>> = coroutineScope {
            words.map { word ->
                async {
                    semaphore.withPermit {
                        kotlinx.coroutines.delay(250)
                        val first = runCatching { searchWithRetry(source, word, 1, categories) }.getOrNull()
                        word to (first?.pages ?: 1).coerceIn(1, 50)
                    }
                }
            }.map { it.await() }
        }
        val wordSets: List<List<ComicSummary>> = kotlinx.coroutines.withTimeout(120_000) {
            coroutineScope {
                wordPageCounts.map { (word, pages) ->
                    async {
                        (1..pages).mapNotNull { p ->
                            semaphore.withPermit {
                                kotlinx.coroutines.delay(250)
                                runCatching { searchWithRetry(source, word, p, categories) }.getOrNull()?.items
                            }
                        }.flatten()
                    }
                }.map { it.await() }
            }
        }
        val wordIds = wordSets.map { set -> set.map { it.id }.toSet() }
        val common = wordIds[0].filter { id -> wordIds.all { it.contains(id) } }
        targetPages[target.key] = 50
        targetEnded[target.key] = true
        return common
            .mapNotNull { id -> wordSets[0].firstOrNull { it.id == id } }
            .sortedByComicSort(ComicSort.DD)
    }

    /** 单词/多词搜索，失败自动重试 */
    private suspend fun searchWithRetry(
        source: Source,
        word: String,
        page: Int,
        categories: List<String> = emptyList(),
    ): com.pika.core.model.PageResult<ComicSummary> {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return source.search(word, page, ComicSort.DD, categories = categories)
            } catch (e: Exception) {
                // 取消必须立刻放行：新一次刷新接管时旧重试不能再继续烧请求
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                if (attempt < 2) kotlinx.coroutines.delay(500)
            }
        }
        throw last ?: RuntimeException("search failed")
    }

    /** 合并新拉取的漫画：按 id 去重、按更新时间（ISO 前缀字典序）由近至远排序 */
    private fun mergeIntoFeed(newItems: List<ComicSummary>, append: Boolean = false) {
        val base = if (append) _followFeed.value else emptyList()
        val merged = (base + newItems)
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
        _followFeed.value = merged
        if (merged.isEmpty() && !append) {
            _followEmptyHint.value = "关注的内容暂无更新"
        }
    }
}