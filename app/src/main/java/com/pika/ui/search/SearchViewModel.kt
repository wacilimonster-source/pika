package com.pika.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 搜索 VM：关键词分页搜索（当前源）。
 * 支持筛选：排序 / 标签。
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

    // ---- 筛选状态（StateFlow，Compose 可观察） ----
    private val _sort = MutableStateFlow(ComicSort.DD)
    val sort: StateFlow<ComicSort> = _sort

    private val _tags = MutableStateFlow<Set<String>>(emptySet())
    val tags: StateFlow<Set<String>> = _tags

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    var currentPage: Int = 1
        private set

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

    fun updateFilter(
        sort: ComicSort = _sort.value,
        tags: Set<String> = _tags.value,
    ) {
        _sort.value = sort
        _tags.value = tags
        // 关键词为空时仅按标签筛选（标签搜索不依赖关键词）
        search(_keyword.value, page = 1)
    }

    fun resetFilters() = updateFilter(
        sort = ComicSort.DD,
        tags = emptySet(),
    )

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
                val result = SourceManager.current().search(
                    keyword = keyword,
                    page = page,
                    sort = _sort.value,
                    tags = _tags.value.toList(),
                )
                _comics.value = result.items
                _totalPages.value = result.pages.coerceAtLeast(1)
                _endReached.value = page >= result.pages
                currentPage = page
            } catch (e: Exception) {
                // 搜索失败保留已有结果
            } finally {
                _loading.value = false
            }
        }
    }
}
