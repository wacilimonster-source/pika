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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 关注来源类型 */
private enum class FollowTargetType { AUTHOR, KEYWORD }

/** 一个关注来源：作者 / 组合关键词（空格连接） */
private data class FollowTarget(
    val key: String,
    val type: FollowTargetType,
    val name: String,
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

    private val _rankComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val rankComics: StateFlow<List<ComicSummary>> = _rankComics.asStateFlow()

    private val _rankType = MutableStateFlow("H24")
    val rankType: StateFlow<String> = _rankType.asStateFlow()

    private val _rankLoading = MutableStateFlow(false)
    val rankLoading: StateFlow<Boolean> = _rankLoading.asStateFlow()

    private val _rankError = MutableStateFlow<String?>(null)
    val rankError: StateFlow<String?> = _rankError.asStateFlow()

    /** 各关注来源当前已加载到的页数（key -> page） */
    private var targetPages = mutableMapOf<String, Int>()

    /** 各关注来源是否已到末页 */
    private var targetEnded = mutableMapOf<String, Boolean>()

    private var targets: List<FollowTarget> = emptyList()

    private var followLoadingJob: kotlinx.coroutines.Job? = null

    /** 加载指定排行榜（日 H24 / 周 D7 / 月 D30） */
    fun loadRank(type: String) {
        if (_rankType.value == type && _rankComics.value.isNotEmpty() && _rankError.value == null) return
        _rankType.value = type
        _rankLoading.value = true
        _rankError.value = null
        viewModelScope.launch {
            try {
                _rankComics.value = SourceManager.current().rank(type)
            } catch (e: Exception) {
                _rankComics.value = emptyList()
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
                add(FollowTarget("k_$name", FollowTargetType.KEYWORD, name))
            }
        }
    }

    /** 下拉刷新：所有来源重新从第 1 页拉取 */
    fun refresh() {
        rebuildTargets()
        if (targets.isEmpty()) {
            _followFeed.value = emptyList()
            _followEndReached.value = true
            _followEmptyHint.value = "还没有关注内容，去「我的 → 关注管理」添加关键词关注"
            return
        }
        _followEmptyHint.value = null
        _followLoading.value = true
        followLoadingJob?.cancel()
        followLoadingJob = viewModelScope.launch {
            targetPages.clear()
            targetEnded.clear()
            val result = fetchTargetPage(1)
            mergeIntoFeed(result)
            _followLoading.value = false
            _followEndReached.value = targetEnded.values.all { it }
        }
    }

    /** 滚动加载：每个来源拉下一页，合并排序追加 */
    fun loadMore() {
        if (_followLoading.value || _followEndReached.value) return
        if (targets.isEmpty()) return
        _followLoading.value = true
        followLoadingJob = viewModelScope.launch {
            val next = targetPages.values.maxOrNull()?.plus(1) ?: 2
            val result = fetchTargetPage(next)
            mergeIntoFeed(result, append = true)
            _followLoading.value = false
            _followEndReached.value = targetEnded.values.all { it }
        }
    }

    /**
     * 拉取所有来源的指定页。串行执行：哔咔服务端对高频并发请求会挂起（限速 ~2/s），
     * 多词拉取一次几十页，必须与其他来源错开；单词来源排前尽快出内容。
     * 已到末页的来源直接跳过。
     */
    private suspend fun fetchTargetPage(page: Int): List<ComicSummary> {
        val source = SourceManager.current()
        val result = mutableListOf<ComicSummary>()
        val ordered = targets.sortedBy {
            if (it.type == FollowTargetType.KEYWORD && it.name.isNotBlank() && it.name.split(Regex("\\s+")).size > 1) 1 else 0
        }
        for (target in ordered) {
            if (targetEnded[target.key] == true) continue
            result += try {
                when (target.type) {
                    // 作者作品用全文搜索（关键字=作者名）拉取：浏览接口不带时间字段，
                    // 搜索接口按更新时间返回（实测作者名可完全匹配该作者全部作品）。
                    FollowTargetType.AUTHOR ->
                        source.search(keyword = target.name, page = page, sort = ComicSort.DD)
                            .also { r ->
                                targetPages[target.key] = page
                                if (page >= r.pages) targetEnded[target.key] = true
                            }.items
                    FollowTargetType.KEYWORD ->
                        fetchKeywordPage(source, target, page)
                }
            } catch (e: Exception) {
                targetEnded[target.key] = true
                emptyList()
            }
        }
        return result
    }

    /**
     * 组合关键词拉取：服务端不支持空格分词，每个词分别全文搜索（标题/标签/简介）取 id 交集，
     * 保证"且"关系且不误杀（词可分别命中标题或标签）。
     */
    private suspend fun fetchKeywordPage(
        source: Source,
        target: FollowTarget,
        startPage: Int,
    ): List<ComicSummary> {
        val words = target.name.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        if (words.size <= 1) {
            val result = source.search(keyword = target.name, page = startPage, sort = ComicSort.DD)
            targetPages[target.key] = startPage
            if (startPage >= result.pages) targetEnded[target.key] = true
            return result.items
        }
        // 多词关注项：每词按第 1 页响应的 pages 拉取全部可返回页（服务端最多 50 页）取交集，
        // 一次拉完（无需滚动分页）；Semaphore 控制并发避免限流（实测并发 4 安全）。
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
                        val first = runCatching { searchWithRetry(source, word, 1) }.getOrNull()
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
                                runCatching { searchWithRetry(source, word, p) }.getOrNull()?.items
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
    ): com.pika.core.model.PageResult<ComicSummary> {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return source.search(word, page, ComicSort.DD)
            } catch (e: Exception) {
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