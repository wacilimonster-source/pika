package com.pika.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 首页浏览 VM：分类 + 内容流（分页）。
 * 走 SourceManager 当前源，源切换后自动重载。
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

    var currentPage: Int = 1
        private set

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
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val result = SourceManager.current().browse(page = page, category = category)
                _comics.value = if (page == 1) result.items else _comics.value + result.items
                _endReached.value = page >= result.pages
                currentPage = page
            } catch (e: Exception) {
                if (page == 1 && _comics.value.isEmpty()) {
                    _error.value = e.message ?: "加载失败"
                }
            } finally {
                _loading.value = false
            }
        }
    }
}