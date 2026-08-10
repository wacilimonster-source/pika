package com.pika.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicDetail
import com.pika.core.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 漫画详情 VM：基本信息 + 章节列表（当前源） */
class ComicDetailViewModel : ViewModel() {

    private val _comic = MutableStateFlow<ComicDetail?>(null)
    val comic: StateFlow<ComicDetail?> = _comic

    private val _chapters = MutableStateFlow<List<ComicChapter>>(emptyList())
    val chapters: StateFlow<List<ComicChapter>> = _chapters

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    var loadedComicId: String = ""
        private set

    fun load(comicId: String) {
        if (loadedComicId == comicId && _comic.value != null) return
        loadedComicId = comicId
        viewModelScope.launch {
            try {
                _comic.value = SourceManager.current().comicDetail(comicId)
            } catch (e: Exception) {
                // 详情失败：显示空态
            }
        }
        viewModelScope.launch {
            _loading.value = true
            try {
                _chapters.value = SourceManager.current().chapters(comicId)
            } catch (e: Exception) {
                // 章节失败保留已有数据
            } finally {
                _loading.value = false
            }
        }
    }
}