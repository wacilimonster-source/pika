package com.pika.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.PageResult
import com.pika.core.model.sortedByComicSort
import com.pika.core.source.Source
import com.pika.core.source.SourceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 搜索 VM：关键词分页搜索（当前源）。
 * 支持多关键词（空格分隔，服务端全文搜索覆盖标题/标签）与排序筛选。
 */
class SearchViewModel : ViewModel() {

    private val _hotWords = MutableStateFlow<List<String>>(emptyList())
    val hotWords: StateFlow<List<String>> = _hotWords

    /** 官方标签词表（空 = 源不支持标签筛选） */
    private val _tags = MutableStateFlow<List<String>>(emptyList())
    val tags: StateFlow<List<String>> = _tags

    /** 当前选中的标签筛选（null = 不过滤） */
    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag

    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics

    /** 初始加载中（用于显示加载指示器）；多词后台加载时不阻塞 UI */
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    /** 多词后台加载中（初始显示后，后台继续拉取剩余页时为 true） */
    private val _multiLoading = MutableStateFlow(false)
    val multiLoading: StateFlow<Boolean> = _multiLoading

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    // ---- 筛选状态（Compose 可观察） ----
    private val _sort = MutableStateFlow(ComicSort.DD)
    val sort: StateFlow<ComicSort> = _sort

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage

    /** 当前搜索任务（用于中途取消） */
    private var searchJob: Job? = null

    /** 用于列表滚动位置恢复 */
    private var _savedFirstVisibleIndex: Int = 0
    val savedFirstVisibleIndex: Int get() = _savedFirstVisibleIndex

    private var _savedCurrentPage: Int = 1
    val savedCurrentPage: Int get() = _savedCurrentPage

    /** 是否已恢复过（首次组成为 false，之后为 true） */
    var isScrollStateRestored: Boolean = false
        private set

    /** 保存列表滚动位置（ DisposableEffect ON_PAUSE 时调用） */
    fun saveScrollState(firstVisibleIndex: Int, currentPage: Int) {
        _savedFirstVisibleIndex = firstVisibleIndex
        _savedCurrentPage = currentPage
    }

    /** 通知滚动状态已恢复（用于 LaunchedEffect key 变化触发） */
    fun markScrollStateRestored() {
        isScrollStateRestored = true
    }

    /** 翻页时通知 Screen 滚到顶部 */
    private val _shouldScrollToTop = MutableStateFlow(0)
    val shouldScrollToTop: StateFlow<Int> = _shouldScrollToTop

    /** 多词搜索专用 scope（所有子协程通过它创建，便于中途取消） */
    private var multiSearchJob: Job? = null

    /** 多词检索并发上限与请求间隔 */
    private val multiConcurrency = 4

    /** 单页请求失败重试次数 */
    private val pageRetryCount = 2

    /** 多词搜索时每页展示数量 */
    private val pageSize = 20

    // ---- 多词渐进式加载的临时状态（在一次多词搜索期间使用） ----
    private var _multiAllComics: MutableList<ComicSummary> = mutableListOf()
    /** 最近一次已确认的交集 id 集合 */
    private var _confirmedIntersectionIds: Set<String> = emptySet()

    /** 多词搜索是否已完成（所有词所有页都拉完，或被取消） */
    private val _multiSearchComplete = MutableStateFlow(false)
    val multiSearchComplete: StateFlow<Boolean> = _multiSearchComplete

    /** 当前搜索的词列表（发布结果时校验未过期：词与当前搜索一致才发布） */
    private var _activeSearchWords: List<String> = emptyList()

    fun loadHotWords() {
        if (_hotWords.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                _hotWords.value = SourceManager.current().hotWords()
            } catch (e: Exception) {
                // 热搜失败忽略
            }
        }
    }

    /** 加载官方标签词表（失败静默：词表为空时 UI 隐藏筛选入口） */
    fun loadTags() {
        if (_tags.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                _tags.value = SourceManager.current().tags()
            } catch (e: Exception) {
                // 词表失败忽略（视为源不支持）
            }
        }
    }

    /** 选择标签筛选（null = 清除），立即触发搜索 */
    fun selectTag(tag: String?) {
        if (_selectedTag.value == tag) return
        _selectedTag.value = tag
        search(_keyword.value, page = 1)
    }

    fun updateFilter(sort: ComicSort = _sort.value) {
        _sort.value = sort
        search(_keyword.value, page = 1)
    }

    fun resetFilters() = updateFilter(sort = ComicSort.DD)

    /** 完全重置搜索状态（含标签筛选） */
    fun resetAll() {
        _selectedTag.value = null
        resetFilters()
        _keyword.value = ""
        _comics.value = emptyList()
        _endReached.value = false
        _totalPages.value = 1
    }

    fun search(keyword: String, page: Int) {
        searchJob?.cancel()
        multiSearchJob?.cancel()
        _comics.value = emptyList()
        _loading.value = true
        _multiLoading.value = false
        _endReached.value = false
        _keyword.value = keyword
        _currentPage.value = 1
        _multiAllComics.clear()
        _confirmedIntersectionIds = emptySet()
        _multiSearchComplete.value = false

        multiSearchJob = viewModelScope.launch {
            try {
                val source = SourceManager.current()
                val words = keyword.split(Regex("\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                _activeSearchWords = words
                val tagFilter = _selectedTag.value
                if (words.size <= 1 && tagFilter == null) {
                    _multiSearchComplete.value = true
                    val result = source.search(
                        keyword = keyword,
                        page = page,
                        sort = _sort.value,
                    )
                    if (_keyword.value != keyword) return@launch
                    _comics.value = result.items
                    _totalPages.value = result.pages.coerceAtLeast(1)
                    _endReached.value = page >= result.pages
                    _currentPage.value = page
                } else {
                    computeMultiWordIntersection(source, words, page, this, tagFilter)
                }
            } finally {
                _loading.value = false
            }
        }
    }

    /**
     * 多词/标签过滤的渐进式加载：
     * 1. 先获取每词的总页数
     * 2. 对每词逐页拉取，逐步扩展交集集合
     * 3. 够 1 页（20 条）后立即显示；后台继续拉完所有页
     * 4. 全部拉完后，启用完整客户端分页（无超时上限）
     * @param startPage 起始页（来自 jumpToPage 或初始搜索）
     * @param tagFilter 选中标签（非空时结果只保留 tags 含该标签的作品，语义：关键词 AND 标签）
     */
    private suspend fun computeMultiWordIntersection(
        source: Source,
        words: List<String>,
        startPage: Int,
        scope: CoroutineScope,
        tagFilter: String? = null,
    ) {
        // 客户端过滤：搜索接口返回的 doc 自带 tags，无需额外请求
        fun applyTagFilter(items: List<ComicSummary>): List<ComicSummary> =
            if (tagFilter == null) items else items.filter { it.tags.contains(tagFilter) }

        coroutineScope {
            val semaphore = Semaphore(multiConcurrency)
            val wordPageCountDefs = words.map { word ->
                scope.async {
                    delay(250)
                    val first = runCatching { searchWithRetry(source, word, startPage) }.getOrNull()
                    word to (first?.pages ?: 1).coerceIn(1, 50)
                }
            }
            val wordPageCounts = wordPageCountDefs.map { it.await() }

            val wordProgress = words.associateWith { startPage - 1 }.toMutableMap()
            val wordIdSets = mutableMapOf<String, MutableSet<String>>().apply {
                words.forEach { put(it, mutableSetOf()) }
            }

            // 阶段 1：拉每词起始页，建立初始交集
            for (word in words) {
                val page = (wordProgress[word] ?: 0) + 1
                val items = applyTagFilter(semaphore.withPermit {
                    delay(250)
                    runCatching { searchWithRetry(source, word, page) }.getOrNull()?.items ?: emptyList()
                })
                if (items.isNotEmpty()) {
                    wordIdSets[word]?.addAll(items.map { it.id })
                    _multiAllComics.addAll(items)
                    wordProgress[word] = page
                }
            }
            val intersection = computeIntersection(wordIdSets, words)
            _confirmedIntersectionIds = intersection
            if (words == _activeSearchWords) {
                publishDisplay(intersection, complete = false)
            }

            // 阶段 2：继续拉取剩余页，逐步扩展交集（无超时上限，持续拉取直到所有词都拉完）
            var madeProgress = true
            while (madeProgress) {
                madeProgress = false
                val pendingWords = wordPageCounts.filter { (w, totalPages) ->
                    (wordProgress[w] ?: 0) < totalPages
                }
                if (pendingWords.isEmpty()) break

                madeProgress = true
                val pageJobs = pendingWords.map { (w, _) ->
                    scope.async {
                        val nextPage = (wordProgress[w] ?: 0) + 1
                        val items = applyTagFilter(semaphore.withPermit {
                            delay(250)
                            runCatching { searchWithRetry(source, w, nextPage) }.getOrNull()?.items ?: emptyList()
                        })
                        w to items
                    }
                }
                for (job in pageJobs) {
                    val (w, items) = job.await()
                    if (items.isNotEmpty()) {
                        _multiAllComics.addAll(items)
                        wordIdSets[w]?.addAll(items.map { it.id })
                        wordProgress[w] = (wordProgress[w] ?: 0) + 1
                    }
                }

                val newIntersection = computeIntersection(wordIdSets, words)
                if (newIntersection != _confirmedIntersectionIds && words == _activeSearchWords) {
                    _confirmedIntersectionIds = newIntersection
                    publishDisplay(newIntersection, complete = false)
                }
            }

            // 全部拉完，发布最终结果
            if (words == _activeSearchWords) {
                publishFinalResult(_confirmedIntersectionIds)
            }
        }
    }

    /** 计算各词 id 集合的交集 */
    private fun computeIntersection(wordIdSets: Map<String, Set<String>>, words: List<String>): Set<String> {
        if (words.isEmpty()) return emptySet()
        var result = wordIdSets[words[0]] ?: emptySet()
        for (i in 1 until words.size) {
            result = result.intersect(wordIdSets[words[i]] ?: emptySet())
        }
        return result
    }

    /** 从当前积累的 comics 构建展示列表（取当前确认的交集，按 sort 排序后取前 pageSize 条） */
    private fun buildDisplay(): List<ComicSummary> {
        return _multiAllComics
            .filter { it.id in _confirmedIntersectionIds }
            .distinctBy { it.id }
            .sortedByComicSort(_sort.value)
            .take(pageSize)
    }

    /** 构建指定页的展示数据（从已确认交集中分页） */
    private fun buildDisplayForPage(page: Int): List<ComicSummary> {
        return _multiAllComics
            .filter { it.id in _confirmedIntersectionIds }
            .distinctBy { it.id }
            .sortedByComicSort(_sort.value)
            .drop((page - 1) * pageSize)
            .take(pageSize)
    }

    /** 发布中间结果（够 1 页即显示），后台继续加载 */
    private fun publishDisplay(intersection: Set<String>, complete: Boolean) {
        val display = buildDisplay()
        if (display.isNotEmpty()) {
            _comics.value = display
            _loading.value = false
            _multiLoading.value = !complete
            _endReached.value = complete
            _totalPages.value = ((intersection.size + pageSize - 1) / pageSize).coerceAtLeast(1)
        }
    }

    /** 发布最终结果，多词搜索完成 */
    private fun publishFinalResult(intersection: Set<String>) {
        _confirmedIntersectionIds = intersection
        _comics.value = buildDisplay()
        _loading.value = false
        _multiLoading.value = false
        _endReached.value = true
        _multiSearchComplete.value = true
        _totalPages.value = ((intersection.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    }

    /**
     * 排序切换：
     * - 多词/带标签场景：直接对已积累的全部 comics 重排（不重新请求）
     * - 单词场景：重新请求（服务端分页排序）
     */
    fun updateSortOnly(sort: ComicSort) {
        _sort.value = sort
        val words = _keyword.value.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.size > 1 || _selectedTag.value != null) {
            _comics.value = _multiAllComics
                .filter { it.id in _confirmedIntersectionIds }
                .distinctBy { it.id }
                .sortedByComicSort(_sort.value)
        } else {
            search(_keyword.value, page = currentPage.value)
        }
    }

    /** 跳转到指定页（多词/带标签：客户端分页；单词：服务端分页） */
    fun jumpToPage(page: Int) {
        _shouldScrollToTop.value++
        val words = _keyword.value.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (words.size > 1 || _selectedTag.value != null) {
            // 多词/带标签：直接用已缓存的全量数据做客户端分页
            _currentPage.value = page
            _comics.value = buildDisplayForPage(page)
            _endReached.value = page >= _totalPages.value
        } else {
            search(_keyword.value, page)
        }
    }

    /** 加载更多（多词场景：交集完成后按页追加） */
    fun loadMore() {
        if (_loading.value) return
        if (!_multiSearchComplete.value) return

        val nextPage = currentPage.value + 1
        val pageComics = buildDisplayForPage(nextPage)
        if (pageComics.isEmpty()) {
            _endReached.value = true
            return
        }
        _comics.value = _comics.value + pageComics
        _currentPage.value = nextPage
        _endReached.value = nextPage >= _totalPages.value
    }

    /** 单页搜索，失败自动重试 */
    private suspend fun searchWithRetry(
        source: Source,
        word: String,
        page: Int,
    ): PageResult<ComicSummary> {
        var last: Exception? = null
        repeat(pageRetryCount + 1) { attempt ->
            try {
                return source.search(word, page, _sort.value)
            } catch (e: Exception) {
                last = e
                if (attempt < pageRetryCount) delay(500)
            }
        }
        throw last ?: RuntimeException("search failed")
    }
}
