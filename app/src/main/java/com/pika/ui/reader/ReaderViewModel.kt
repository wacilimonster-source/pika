package com.pika.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 阅读器 VM：加载某章全部图片页（当前源） */
class ReaderViewModel : ViewModel() {

    private val _pages = MutableStateFlow<List<com.pika.core.model.ComicPage>>(emptyList())
    val pages: StateFlow<List<com.pika.core.model.ComicPage>> = _pages

    private val _epTitle = MutableStateFlow("")
    val epTitle: StateFlow<String> = _epTitle

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    fun load(comicId: String, order: Int) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            try {
                _pages.value = SourceManager.current().chapterPages(comicId, order)
            } catch (e: Exception) {
                // 加载失败保持空列表
            } finally {
                _loading.value = false
            }
        }
    }
}