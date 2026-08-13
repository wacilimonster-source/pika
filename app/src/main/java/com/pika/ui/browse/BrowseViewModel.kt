package com.pika.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicDateRange
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicStatus
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 首页浏览 VM：分类 + 内容流（分页）。
 * 走 SourceManager 当前源，源切换后自动重载。
 * 支持排序（哔咔服务端 / 禁漫客户端重排）、连载状态筛选与更新日期范围筛选
 * （均为客户端过滤 + 自动补页）。
 *
 * 缓存优化：rawItems 在切换排序/状态/日期范围时保留，避免重新请求网络。
 * 仅在切换分类或源时清空重载。
 */
class BrowseViewModel : ViewModel() {

    private val _categories = MutableStateFlow<List<ComicCategory>>(emptyList())
    val categories: StateFlow<List<ComicCategory>> = _categories

    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _sort = MutableStateFlow(ComicSort.DD)
    val sort: StateFlow<ComicSort> = _sort

    private val _status = MutableStateFlow(ComicStatus.ALL)
    val status: StateFlow<ComicStatus> = _status

    private val _author = MutableStateFlow<String?>(null)
    val author: StateFlow<String?> = _author

    private val _dateRange = MutableStateFlow<ComicDateRange?>(null)
    val dateRange: StateFlow<ComicDateRange?> = _dateRange

    /** 已拉取的原始数据（未过滤/未重排），供筛选与排序使用 */
    private val rawItems = mutableListOf<ComicSummary>()

    private var category: String? = null

    /** 加载代际：切排序/筛选/换源时递增，使旧加载在 await 后失效，避免并发写脏数据 */
    private var loadToken = 0

    var currentPage: Int = 1
        private set

    private companion object {
        const val FILL_TARGET = 24
        const val MAX_FILL_PAGES = 30
        const val MAX_FILL_PAGES_DATE = 90
    }

    fun loadCategories() {
        viewModelScope.launch {
            try {
                _categories.value = SourceManager.current().categories()
            } catch (e: Exception) {
                // 分类失败不阻塞浏览
            }
        }
    }

    fun loadComics(page: Int, category: String? = null) {
        if (page <= 1) this.category = category
        val token = ++loadToken
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                var p = page.coerceAtLeast(1)
                val maxFill = if (_dateRange.value != null) MAX_FILL_PAGES_DATE else MAX_FILL_PAGES
                while (true) {
                    if (token != loadToken) return@launch
                    val result = SourceManager.current().browse(
                        page = p,
                        category = this@BrowseViewModel.category,
                        sort = _sort.value,
                        author = _author.value,
                    )
                    if (token != loadToken) return@launch
                    if (p <= 1) rawItems.clear()
                    rawItems += result.items
                    currentPage = p
                    _endReached.value = p >= result.pages
                    applyFilterAndSort()
                    if (_endReached.value || _comics.value.size >= FILL_TARGET || p - page >= maxFill) break
                    p++
                }
            } catch (e: Exception) {
                if (token == loadToken && page == 1 && _comics.value.isEmpty()) {
                    _error.value = e.message ?: "加载失败"
                }
            } finally {
                if (token == loadToken) _loading.value = false
            }
        }
    }

    fun setSort(sort: ComicSort) {
        if (_sort.value == sort) return
        _sort.value = sort
        // 缓存命中：直接用已有 rawItems 重滤，不重新请求网络
        if (rawItems.isNotEmpty()) {
            applyFilterAndSort()
            return
        }
        reload()
    }

    fun setStatus(status: ComicStatus) {
        if (_status.value == status) return
        _status.value = status
        if (rawItems.isNotEmpty()) {
            applyFilterAndSort()
            return
        }
        reload()
    }

    fun setDateRange(range: ComicDateRange?) {
        if (_dateRange.value == range) return
        _dateRange.value = range
        if (rawItems.isNotEmpty()) {
            applyFilterAndSort()
            return
        }
        reload()
    }

    fun setAuthor(author: String?) {
        if (_author.value == author) return
        _author.value = author
        reload()
    }

    private fun reload() {
        rawItems.clear()
        _comics.value = emptyList()
        _error.value = null
        _endReached.value = false
        currentPage = 0
        loadComics(page = 1, category = category)
    }

    /** 状态筛选 + 日期范围（客户端）→ 排序（禁漫客户端重排；哔咔服务端已排好无需再动） */
    private fun applyFilterAndSort() {
        val dateRange = _dateRange.value
        val filtered = rawItems.filter {
            when (_status.value) {
                ComicStatus.ALL -> true
                ComicStatus.FINISHED -> it.finished
                ComicStatus.ONGOING -> !it.finished
            } && (dateRange == null || dateRange.matches(it.updatedAt))
        }
        val sorted = when (_sort.value) {
            ComicSort.LD -> filtered.sortedByDescending { it.totalLikes }
            ComicSort.VD -> filtered.sortedByDescending { it.totalViews }
            else -> filtered
        }
        _comics.value = sorted
    }
}
