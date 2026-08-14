package com.pika.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import com.pika.data.AuthorFavourites
import com.pika.data.FollowSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 关注来源类型 */
private enum class FollowTargetType { AUTHOR, KEYWORD, CATEGORY }

/** 一个关注来源：作者名 / 关键词 / 分类 */
private data class FollowTarget(
    val key: String,
    val type: FollowTargetType,
    val name: String,
)

/**
 * 首页数据聚合：排行榜(H24/D7/D30) + 关注信息流。
 * 关注流 = 所有关注来源（收藏作者/关键词/分类标签）的最新作品合并，
 * 按更新时间由近至远排序、按 id 去重，滚动加载（每个来源逐页拉取）。
 */
class HomeViewModel : ViewModel() {

    private val _followFeed = MutableStateFlow<List<ComicSummary>>(emptyList())
    val followFeed: StateFlow<List<ComicSummary>> = _followFeed.asStateFlow()

    private val _followEndReached = MutableStateFlow(false)
    val followEndReached: StateFlow<Boolean> = _followEndReached.asStateFlow()

    private val _followLoading = MutableStateFlow(false)
    val followLoading: StateFlow<Boolean> = _followLoading.asStateFlow()

    private val _followEmptyHint = MutableStateFlow<String?>(null)
    val followEmptyHint: StateFlow<String?> = _followEmptyHint.asStateFlow()

    private val _rankComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val rankComics: StateFlow<List<ComicSummary>> = _rankComics.asStateFlow()

    private val _rankType = MutableStateFlow("H24")
    val rankType: StateFlow<String> = _rankType.asStateFlow()

    private val _rankLoading = MutableStateFlow(false)
    val rankLoading: StateFlow<Boolean> = _rankLoading.asStateFlow()

    private val _rankError = MutableStateFlow<String?>(null)
    val rankError: StateFlow<String?> = _rankError.asStateFlow()

    /** 各关注来源当前已加载到的页数（key -> page） */
    private var targetPages = mutableMapOf<String, Int>()

    /** 各关注来源是否已到末页 */
    private var targetEnded = mutableMapOf<String, Boolean>()

    private var targets: List<FollowTarget> = emptyList()

    private var followLoadingJob: kotlinx.coroutines.Job? = null

    /** 加载指定排行榜（日 H24 / 周 D7 / 月 D30） */
    fun loadRank(type: String) {
        if (_rankType.value == type && _rankComics.value.isNotEmpty() && _rankError.value == null) return
        _rankType.value = type
        _rankLoading.value = true
        _rankError.value = null
        viewModelScope.launch {
            try {
                _rankComics.value = SourceManager.current().rank(type)
            } catch (e: Exception) {
                _rankComics.value = emptyList()
                _rankError.value = e.message ?: "加载排行榜失败"
            } finally {
                _rankLoading.value = false
            }
        }
    }

    /** 首次进入关注 tab：构建关注来源列表 */
    fun ensureFollowTargets() {
        if (targets.isNotEmpty()) return
        rebuildTargets()
    }

    private fun rebuildTargets() {
        targets = buildList {
            AuthorFavourites.get().forEach { add(FollowTarget("a_${it.author}", FollowTargetType.AUTHOR, it.author)) }
            FollowSettings.keywords().forEach { add(FollowTarget("k_$it", FollowTargetType.KEYWORD, it)) }
            FollowSettings.categories().forEach { add(FollowTarget("c_${it.id}", FollowTargetType.CATEGORY, it.title)) }
        }
    }

    /** 下拉刷新：所有来源重新从第 1 页拉取 */
    fun refresh() {
        rebuildTargets()
        if (targets.isEmpty()) {
            _followFeed.value = emptyList()
            _followEndReached.value = true
            _followEmptyHint.value = "还没有关注内容，去「我的 → 关注管理」添加作者 / 关键词 / 分类标签"
            return
        }
        _followEmptyHint.value = null
        _followLoading.value = true
        followLoadingJob?.cancel()
        followLoadingJob = viewModelScope.launch {
            targetPages.clear()
            targetEnded.clear()
            val result = fetchTargetPage(1)
            mergeIntoFeed(result)
            _followLoading.value = false
            _followEndReached.value = targetEnded.values.all { it }
        }
    }

    /** 滚动加载：每个来源拉下一页，合并排序追加 */
    fun loadMore() {
        if (_followLoading.value || _followEndReached.value) return
        if (targets.isEmpty()) return
        _followLoading.value = true
        followLoadingJob = viewModelScope.launch {
            val next = targetPages.values.maxOrNull()?.plus(1) ?: 2
            val result = fetchTargetPage(next)
            mergeIntoFeed(result, append = true)
            _followLoading.value = false
            _followEndReached.value = targetEnded.values.all { it }
        }
    }

    /** 拉取所有来源的指定页（并发，失败项静默跳过并视为末页） */
    private suspend fun fetchTargetPage(page: Int): List<ComicSummary> = coroutineScope {
        val source = SourceManager.current()
        targets.mapNotNull { target ->
            async {
                try {
                    when (target.type) {
                        FollowTargetType.AUTHOR ->
                            source.browse(page = page, category = null, sort = ComicSort.DD, author = target.name)
                        FollowTargetType.KEYWORD ->
                            source.search(keyword = target.name, page = page, sort = ComicSort.DD)
                        FollowTargetType.CATEGORY ->
                            source.browse(page = page, category = target.key.removePrefix("c_"), sort = ComicSort.DD)
                    }.also { result ->
                        targetPages[target.key] = page
                        if (page >= result.pages) targetEnded[target.key] = true
                    }.items
                } catch (e: Exception) {
                    targetEnded[target.key] = true
                    emptyList()
                }
            }
        }.mapNotNull { it.await() }.flatten()
    }

    /** 合并新拉取的漫画：按 id 去重、按更新时间（ISO 前缀字典序）由近至远排序 */
    private fun mergeIntoFeed(newItems: List<ComicSummary>, append: Boolean = false) {
        val base = if (append) _followFeed.value else emptyList()
        val merged = (base + newItems)
            .distinctBy { it.id }
            .sortedByDescending { it.updatedAt }
        _followFeed.value = merged
        if (merged.isEmpty() && !append) {
            _followEmptyHint.value = "关注的内容暂无更新"
        }
    }
}