package com.pika.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.model.sortedByComicSort
import com.pika.core.source.Source
import com.pika.core.source.SourceManager
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

    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword

    // ---- 筛选状态（Compose 可观察） ----
    private val _sort = MutableStateFlow(ComicSort.DD)
    val sort: StateFlow<ComicSort> = _sort

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    var currentPage: Int = 1
        private set

    /** 多词检索结果缓存（key = 关键词），按关键字缓存交集全量结果，切换排序时只重排不重新请求 */
    private val multiCache = mutableMapOf<String, List<ComicSummary>>()

    /** 多词检索并发上限与请求间隔（实测服务端对系统 TLS 并发 6 无限制，留余量取 4） */
    private val multiConcurrency = 4

    /** 单页请求失败重试次数 */
    private val pageRetryCount = 2

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

    fun updateFilter(sort: ComicSort = _sort.value) {
        _sort.value = sort
        search(_keyword.value, page = 1)
    }

    fun resetFilters() = updateFilter(sort = ComicSort.DD)

    /** 完全重置搜索状态（清空结果 + 筛选 + 关键词） */
    fun resetAll() {
        resetFilters()
        _keyword.value = ""
        _comics.value = emptyList()
        _endReached.value = false
        _totalPages.value = 1
    }

    fun search(keyword: String, page: Int) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _keyword.value = keyword
            try {
                val source = SourceManager.current()
                val words = keyword.split(Regex("\\s+"))
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (words.size <= 1) {
                    val result = source.search(
                        keyword = keyword,
                        page = page,
                        sort = _sort.value,
                    )
                    _comics.value = result.items
                    _totalPages.value = result.pages.coerceAtLeast(1)
                    _endReached.value = page >= result.pages
                    currentPage = page
                } else {
                    // 多关键词（且关系）：服务端不支持空格分词与标签筛选，
                    // 每个词分别全文搜索（标题/标签/简介），取 id 交集。
                    // 每词按第 1 页响应的 pages 拉取全部可返回页（服务端最多 50 页），
                    // 避免交集作品分布靠后导致无结果。
                    val cacheKey = keyword.trim()
                    val cached = multiCache[cacheKey]
                    if (cached != null) {
                        _comics.value = cached.sortedByComicSort(_sort.value)
                        _totalPages.value = 1
                        _endReached.value = true
                        currentPage = 1
                    } else {
                        val semaphore = Semaphore(multiConcurrency)
                        val wordPageCounts: List<Pair<String, Int>> = coroutineScope {
                            words.map { word ->
                                async {
                                    semaphore.withPermit {
                                        delay(250)
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
                                                delay(250)
                                                runCatching { searchWithRetry(source, word, p) }.getOrNull()?.items
                                            }
                                        }.flatten()
                                    }
                                }.map { it.await() }
                            }
                        }
                        val wordIds = wordSets.map { set -> set.map { it.id }.toSet() }
                        val common = wordIds[0].filter { id -> wordIds.all { it.contains(id) } }
                        val inter = common
                            .mapNotNull { id -> wordSets[0].firstOrNull { it.id == id } }
                        multiCache[cacheKey] = inter
                        _comics.value = inter.sortedByComicSort(_sort.value)
                        _totalPages.value = 1
                        _endReached.value = true
                        currentPage = 1
                    }
                }
            } catch (e: Exception) {
                // 搜索失败保留已有结果
            } finally {
                _loading.value = false
            }
        }
    }

    /** 单页搜索，失败自动重试 */
    private suspend fun searchWithRetry(
        source: Source,
        word: String,
        page: Int,
    ): com.pika.core.model.PageResult<ComicSummary> {
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

/** 按当前排序方式对多词交集结果排序（哔咔服务端排序只影响翻页集合，集合不变） */

