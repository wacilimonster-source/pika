package com.pika.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicComment
import com.pika.core.model.ComicDetail
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import com.pika.core.log.LogStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 漫画详情 VM：基本信息 + 章节列表 + 相关推荐 + 评论区（当前源） */
class ComicDetailViewModel : ViewModel() {

    private val _comic = MutableStateFlow<ComicDetail?>(null)
    val comic: StateFlow<ComicDetail?> = _comic

    private val _chapters = MutableStateFlow<List<ComicChapter>>(emptyList())
    val chapters: StateFlow<List<ComicChapter>> = _chapters

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // ---- 相关推荐 ----
    private val _recommendations = MutableStateFlow<List<ComicSummary>>(emptyList())
    val recommendations: StateFlow<List<ComicSummary>> = _recommendations

    // ---- 评论区 ----
    private val _comments = MutableStateFlow<List<ComicComment>>(emptyList())
    val comments: StateFlow<List<ComicComment>> = _comments

    private val _commentLoading = MutableStateFlow(false)
    val commentLoading: StateFlow<Boolean> = _commentLoading

    private val _commentEndReached = MutableStateFlow(false)
    val commentEndReached: StateFlow<Boolean> = _commentEndReached

    private val _commentError = MutableStateFlow<String?>(null)
    val commentError: StateFlow<String?> = _commentError

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending

    var commentPage: Int = 1
        private set

    // 楼中楼缓存：commentId -> 已加载子评论
    private val _subComments = MutableStateFlow<Map<String, List<ComicComment>>>(emptyMap())
    val subComments: StateFlow<Map<String, List<ComicComment>>> = _subComments

    private val _loadingSubIds = MutableStateFlow<Set<String>>(emptySet())
    val loadingSubIds: StateFlow<Set<String>> = _loadingSubIds

    private val _replyingTo = MutableStateFlow<String?>(null)
    val replyingTo: StateFlow<String?> = _replyingTo

    var loadedComicId: String = ""
        private set

    /** 加载代数：切换漫画时自增，旧协程回调前校验，防止脏数据覆盖新漫画状态 */
    private var loadGeneration = 0
    private var loadJob: kotlinx.coroutines.Job? = null

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var commentSupported: Boolean = true
        private set

    /** 本地历史进度（上次阅读到第几话第几页），无则 null */
    private val _lastProgress = MutableStateFlow<com.pika.data.ReaderPrefs.Progress?>(null)
    val lastProgress: StateFlow<com.pika.data.ReaderPrefs.Progress?> = _lastProgress

    fun load(comicId: String) {
        if (loadedComicId == comicId && _comic.value != null) return
        loadedComicId = comicId
        // 取消上一本漫画还在跑的请求，避免旧结果覆盖新漫画状态
        loadJob?.cancel()
        loadJob = null
        val gen = ++loadGeneration
        _error.value = null
        // 读取本地历史进度（不阻塞主线程）
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (gen != loadGeneration) return@launch
            _lastProgress.value = runCatching {
                com.pika.data.ReaderPrefs.current().lastProgress(comicId)
            }.getOrNull()
        }
        loadJob = viewModelScope.launch {
            val chaptersJob = launch {
                _loading.value = true
                try {
                    val list = SourceManager.current().chapters(comicId)
                    if (gen != loadGeneration) return@launch
                    _chapters.value = list
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 章节失败保留已有数据
                } finally {
                    if (gen == loadGeneration) _loading.value = false
                }
            }
            try {
                val detail = SourceManager.current().comicDetail(comicId)
                if (gen != loadGeneration) return@launch
                _comic.value = detail.also {
                    // 列表接口不返回更新时间，详情拉到就记录，供关注流回填展示
                    if (it.updatedAt.isNotBlank()) {
                        com.pika.data.UpdatedAtCache.put(it.id, it.updatedAt)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == loadGeneration) {
                    _error.value = e.message ?: "加载失败"
                }
            }
            chaptersJob.join()
        }
        loadRecommendations(comicId, gen)
        loadComments(comicId, page = 1, gen = gen)
    }

    /** 相关推荐（源不支持时静默失败） */
    fun loadRecommendations(comicId: String, gen: Int = loadGeneration) {
        viewModelScope.launch {
            try {
                val list = SourceManager.current().recommendations(comicId)
                if (gen == loadGeneration) _recommendations.value = list
            } catch (e: Exception) {
                if (gen == loadGeneration) _recommendations.value = emptyList()
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
            // 单章页数运行时才可知，传 0 由 runTask 拉取真实页数后回填
            pageCount = 0,
        )
    }

    /** 下载整本漫画（跳过已下载章节） */
    fun downloadAll(comicId: String, comic: ComicDetail?, chapters: List<ComicChapter>) {
        com.pika.core.download.DownloadManager.enqueueAll(
            comicId = comicId,
            comicTitle = comic?.title ?: comicId,
            coverUrl = comic?.coverUrl ?: "",
            chapters = chapters.map { it.order to it.title },
        )
    }

    // ── 收藏 ──────────────────────────────────────────────────────────────
    private var favouriteSupported: Boolean = true
    private val _favourited = MutableStateFlow(false)
    val favourited: StateFlow<Boolean> = _favourited

    private val _favouriteError = MutableStateFlow<String?>(null)
    val favouriteError: StateFlow<String?> = _favouriteError

    /** 当前源是否支持收藏 */
    fun canFavourite(): Boolean = favouriteSupported

    /** 收藏 / 取消收藏（切换） */
    fun favourite() {
        val comicId = loadedComicId
        if (comicId.isEmpty()) return
        viewModelScope.launch {
            try {
                val now = SourceManager.current().favourite(comicId, !_favourited.value)
                // 以源返回的真实状态回写，避免切换型接口本地失步
                _favourited.value = now
                LogStore.log("Detail", "I", "favourite toggled: comic=$comicId, favourited=$now")
            } catch (e: UnsupportedOperationException) {
                favouriteSupported = false
                LogStore.log("Detail", "I", "favourite not supported by current source")
            } catch (e: Exception) {
                val msg = e.message ?: "未知错误"
                _favouriteError.value = msg
                LogStore.log("Detail", "E", "favourite failed: comic=$comicId, error=$msg")
            }
        }
    }

    fun consumeFavouriteError(): String? {
        val v = _favouriteError.value
        _favouriteError.value = null
        return v
    }

    // ── 评论 ──────────────────────────────────────────────────────────────

    /** 分页加载评论（page=1 时重置；gen 用于防止旧漫画的评论写入新页面） */
    fun loadComments(comicId: String, page: Int, gen: Int = loadGeneration) {
        if (_commentLoading.value) return
        if (page > 1 && _commentEndReached.value) return
        viewModelScope.launch {
            _commentLoading.value = true
            _commentError.value = null
            try {
                val result = SourceManager.current().comments(comicId, page)
                if (gen != loadGeneration) return@launch
                _comments.value = if (page == 1) result.items else _comments.value + result.items
                _commentEndReached.value = page >= result.pages
                commentPage = page
            } catch (e: UnsupportedOperationException) {
                commentSupported = false
                _commentError.value = null
            } catch (e: Exception) {
                if (gen == loadGeneration) _commentError.value = e.message ?: "评论加载失败"
            } finally {
                if (gen == loadGeneration) _commentLoading.value = false
            }
        }
    }

    fun setReplyingTo(commentId: String?) {
        _replyingTo.value = commentId
    }

    /** 发表评论 / 回复 */
    fun send(content: String, onSent: (String?) -> Unit = {}) {
        if (_sending.value || content.isBlank()) return
        val comicId = loadedComicId
        val replyId = _replyingTo.value
        viewModelScope.launch {
            _sending.value = true
            try {
                if (replyId != null) {
                    SourceManager.current().replyComment(replyId, content.trim())
                } else {
                    SourceManager.current().sendComment(comicId, content.trim())
                }
                _replyingTo.value = null
                // 重新加载第一页（新评论置顶展示）
                _comments.value = emptyList()
                loadComments(comicId, page = 1)
                onSent(null)
            } catch (e: Exception) {
                onSent(e.message ?: "发送失败")
            } finally {
                _sending.value = false
            }
        }
    }

    /** 加载楼中楼子评论（第一页） */
    fun toggleSubComments(commentId: String) {
        val current = _subComments.value
        if (current.containsKey(commentId)) {
            // 已加载 → 收起
            _subComments.value = current - commentId
            return
        }
        _loadingSubIds.value = _loadingSubIds.value + commentId
        viewModelScope.launch {
            try {
                val result = SourceManager.current().commentChildren(commentId, page = 1)
                _subComments.value = _subComments.value + (commentId to result.items)
            } catch (e: Exception) {
                // 加载子评论失败静默
            } finally {
                _loadingSubIds.value = _loadingSubIds.value - commentId
            }
        }
    }
}
