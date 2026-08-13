package com.pika.ui.reader

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.Coil
import coil.request.ImageRequest
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicPage
import com.pika.core.source.SourceManager
import com.pika.data.ReaderPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 阅读器 VM：加载某章全部图片页（当前源）+ 章节列表（切章用）+ 本地进度。
 */
class ReaderViewModel : ViewModel() {

    private val _pages = MutableStateFlow<List<ComicPage>>(emptyList())
    val pages: StateFlow<List<ComicPage>> = _pages

    private val _chapters = MutableStateFlow<List<ComicChapter>>(emptyList())
    val chapters: StateFlow<List<ComicChapter>> = _chapters

    private val _epTitle = MutableStateFlow("")
    val epTitle: StateFlow<String> = _epTitle

    private val _coverUrl = MutableStateFlow("")
    val coverUrl: StateFlow<String> = _coverUrl

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    var comicId: String = ""
        private set

    var currentOrder: Int = 1
        private set

    private var loadedKey: String = ""

    private var loadJob: Job? = null

    fun load(context: Context, comicId: String, order: Int) {
        if (loadedKey == "$comicId:$order" && _pages.value.isNotEmpty()) return
        loadJob?.cancel()
        loadedKey = "$comicId:$order"
        this.comicId = comicId
        this.currentOrder = order
        loadJob = viewModelScope.launch {
            _loading.value = true
            try {
                withContext(Dispatchers.IO) {
                    // 离线优先：章节已下载则直接读本地文件，弱网/无网也能看
                    val local = com.pika.core.download.DownloadManager.chapterDir(comicId, order)
                        .listFiles()?.filter { it.name.startsWith("page_") && it.length() > 0 }
                        ?.sortedBy { it.name }
                    if (!local.isNullOrEmpty()) {
                        _pages.value = local.mapIndexed { i, f ->
                            com.pika.core.model.ComicPage(i, f.toURI().toString())
                        }
                    } else {
                        _pages.value = SourceManager.current().chapterPages(comicId, order)
                    }
                    _chapters.value = SourceManager.current().chapters(comicId)
                    // 顺便拿封面（历史记录用），失败不影响阅读
                    if (_coverUrl.value.isBlank()) {
                        runCatching {
                            _coverUrl.value = SourceManager.current().comicDetail(comicId).coverUrl.orEmpty()
                        }
                    }
                }
                _epTitle.value = _chapters.value.firstOrNull { it.order == order }?.title.orEmpty()
            } catch (e: Exception) {
                // 加载失败保持空列表
            } finally {
                _loading.value = false
            }
        }
        // 预取章节标题无需等 chapters 加载完：标题留空则由 UI 兜底
        ReaderPrefs.current().let { prefs ->
            prefs.lastProgress(comicId)?.let { p ->
                if (p.order == order) pendingRestorePage = p.pageIndex
            }
        }
    }

    /** 等待 pages 加载完成后由 UI 消费的恢复页（-1 表示无需恢复） */
    @Volatile
    var pendingRestorePage: Int = -1

    /** 跳转到指定章节（加载新章节页面） */
    fun switchChapter(context: Context, order: Int) {
        if (order < 1 || order == currentOrder) return
        loadedKey = ""
        pendingRestorePage = -1
        load(context, comicId, order)
    }

    /** 保存阅读进度（本地，带页码），并刷新"最近阅读"。 */
    fun saveProgress(pageIndex: Int) {
        if (_pages.value.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                ReaderPrefs.current().saveProgress(comicId, currentOrder, pageIndex.coerceAtLeast(0))
            }
        }
    }

    /** 记录最近阅读条目（首页"继续阅读"用）。 */
    fun recordRecentRead(title: String, coverUrl: String, pageIndex: Int) {
        viewModelScope.launch {
            runCatching {
                ReaderPrefs.current().recordRecentRead(
                    comicId = comicId,
                    title = title.ifBlank { "第 $currentOrder 话" },
                    coverUrl = coverUrl,
                    order = currentOrder,
                    pageIndex = pageIndex.coerceAtLeast(0),
                )
            }
        }
    }

    /** 预取前后 N 页图片到内存/磁盘缓存（弱网也顺滑）。 */
    fun preloadNearby(context: Context, visiblePage: Int, range: Int = 2) {
        val pages = _pages.value
        if (pages.isEmpty()) return
        val loader = Coil.imageLoader(context)
        for (i in (visiblePage - range)..(visiblePage + range)) {
            if (i < 0 || i >= pages.size) continue
            val url = pages[i].imageUrl
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey(url)
                    .diskCacheKey(url)
                    .build()
            )
        }
    }

    /** 清除当前加载状态（退出阅读器时避免陈旧数据）。 */
    fun clear() {
        loadJob?.cancel()
        loadJob = null
        loadedKey = ""
        pendingRestorePage = -1
        _pages.value = emptyList()
        _chapters.value = emptyList()
        _epTitle.value = ""
        _loading.value = false
    }
}
