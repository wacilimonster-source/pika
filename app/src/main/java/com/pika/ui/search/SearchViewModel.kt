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
 * 搜索 VM：热搜词 + 关键词分页搜索（当前源）。
 * 支持高级筛选：排序 / 分类 / 标签 / 作者 / 汉化组 / 上传者 / 完结状态。
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

    private val _categories = MutableStateFlow<List<ComicCategory>>(emptyList())
    val categories: StateFlow<List<ComicCategory>> = _categories

    // ---- 筛选状态 ----
    var sort: ComicSort = ComicSort.DD
        private set
    var categoryIds: Set<String> = emptySet()
        private set
    var tags: Set<String> = emptySet()
        private set
    var author: String? = null
        private set
    var chineseTeam: String? = null
        private set
    var uploader: String? = null
        private set
    var finished: Boolean? = null
        private set

    private val _showAdvanced = MutableStateFlow(false)
    val showAdvanced: StateFlow<Boolean> = _showAdvanced

    var currentPage: Int = 1
        private set

    fun toggleAdvanced() {
        _showAdvanced.value = !_showAdvanced.value
    }

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

    fun loadCategories() {
        if (_categories.value.isNotEmpty()) return
        viewModelScope.launch {
            try {
                _categories.value = SourceManager.current().categories()
            } catch (e: Exception) {
                // 分类加载失败忽略
            }
        }
    }

    fun updateFilter(
        sort: ComicSort = this.sort,
        categoryIds: Set<String> = this.categoryIds,
        tags: Set<String> = this.tags,
        author: String? = this.author,
        chineseTeam: String? = this.chineseTeam,
        uploader: String? = this.uploader,
        finished: Boolean? = this.finished,
    ) {
        this.sort = sort
        this.categoryIds = categoryIds
        this.tags = tags
        this.author = author
        this.chineseTeam = chineseTeam
        this.uploader = uploader
        this.finished = finished
        if (_keyword.value.isNotBlank()) search(_keyword.value, page = 1)
    }

    fun resetFilters() = updateFilter(
        sort = ComicSort.DD,
        categoryIds = emptySet(),
        tags = emptySet(),
        author = null,
        chineseTeam = null,
        uploader = null,
        finished = null,
    )

    fun search(keyword: String, page: Int) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _keyword.value = keyword
            try {
                val result = SourceManager.current().search(
                    keyword = keyword,
                    page = page,
                    sort = sort,
                    categories = categoryIds.toList(),
                    tags = tags.toList(),
                    author = author,
                    chineseTeam = chineseTeam,
                    uploader = uploader,
                    finished = finished,
                )
                _comics.value = if (page == 1) result.items else _comics.value + result.items
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
