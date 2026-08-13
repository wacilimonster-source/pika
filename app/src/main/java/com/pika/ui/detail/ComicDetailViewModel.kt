package com.pika.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicDetail
import com.pika.core.source.SourceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 漫画详情 VM：基本信息 + 章节列表（当前源）+ 章节下载入口 */
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

    /** 章节是否已下载（有本地文件） */
    fun isDownloaded(comicId: String, chapter: ComicChapter): Boolean =
        com.pika.core.download.DownloadManager.isDownloaded(comicId, chapter.order)

    /** 下载指定章节（入队，由 DownloadManager 调度） */
    fun downloadChapter(comicId: String, comic: ComicDetail?, chapter: ComicChapter) {
        com.pika.core.download.DownloadManager.enqueue(
            comicId = comicId,
            comicTitle = comic?.title ?: comicId,
            coverUrl = comic?.coverUrl ?: "",
            order = chapter.order,
            epTitle = chapter.title,
            pageCount = _comic.value?.epsCount ?: 1,
        )
    }

    // ── 收藏 ──────────────────────────────────────────────────────────────
    private var favouriteSupported: Boolean = true
    private val _favourited = MutableStateFlow(false)
    val favourited: StateFlow<Boolean> = _favourited

    /** 当前源是否支持收藏 */
    fun canFavourite(): Boolean = favouriteSupported

    /** 收藏 / 取消收藏（切换） */
    fun favourite() {
        val comicId = loadedComicId
        if (comicId.isEmpty()) return
        viewModelScope.launch {
            try {
                val ok = SourceManager.current().favourite(comicId, true)
                if (ok) {
                    _favourited.value = !_favourited.value
                }
            } catch (e: UnsupportedOperationException) {
                favouriteSupported = false
            } catch (e: Exception) {
                // 收藏失败静默
            }
        }
    }
}