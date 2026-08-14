package com.pika.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import com.pika.data.AuthorFavourites
import com.pika.data.FollowSettings
import com.pika.data.RecentRead
import com.pika.data.ReaderPrefs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 首页"关注的更新"分组：一个关注对象（作者/关键词/分类标签）的最新作品 */
data class FollowSection(
    val type: String,
    val name: String,
    val comics: List<ComicSummary>,
)

/** 每个关注对象最多展示的最新作品数 */
private const val SECTION_LIMIT = 6

/**
 * 首页数据聚合：上次浏览记录 + 排行榜(H24) + 个人关注更新（收藏作者/关键词/分类标签）。
 */
class HomeViewModel : ViewModel() {

    private val _recentReads = MutableStateFlow<List<RecentRead>>(emptyList())
    val recentReads: StateFlow<List<RecentRead>> = _recentReads.asStateFlow()

    private val _rankComics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val rankComics: StateFlow<List<ComicSummary>> = _rankComics.asStateFlow()

    private val _rankType = MutableStateFlow("H24")
    val rankType: StateFlow<String> = _rankType.asStateFlow()

    private val _rankLoading = MutableStateFlow(false)
    val rankLoading: StateFlow<Boolean> = _rankLoading.asStateFlow()

    private val _followSections = MutableStateFlow<List<FollowSection>>(emptyList())
    val followSections: StateFlow<List<FollowSection>> = _followSections.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var loaded = false

    /** 首次加载（onResume 时调用，避免每次重建都请求） */
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        refresh()
    }

    /** 加载指定排行榜（日 H24 / 周 D7 / 月 D30） */
    fun loadRank(type: String) {
        if (_rankType.value == type && _rankComics.value.isNotEmpty()) return
        _rankType.value = type
        _rankLoading.value = true
        viewModelScope.launch {
            _rankComics.value = runCatching { SourceManager.current().rank(type) }
                .getOrDefault(emptyList())
                .take(10)
            _rankLoading.value = false
        }
    }

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        _recentReads.value = ReaderPrefs.current().recentReads()
        viewModelScope.launch {
            coroutineScope {
                val follows = async { loadFollowSections() }
                _followSections.value = follows.await()
                if (_rankComics.value.isEmpty()) {
                    _rankComics.value = runCatching { SourceManager.current().rank(_rankType.value) }
                        .getOrDefault(emptyList())
                        .take(10)
                }
            }
            _refreshing.value = false
        }
    }

    private suspend fun loadFollowSections(): List<FollowSection> {
        val authors = AuthorFavourites.get()
        val keywords = FollowSettings.keywords()
        val categories = FollowSettings.categories()
        if (authors.isEmpty() && keywords.isEmpty() && categories.isEmpty()) return emptyList()
        return coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<FollowSection?>>()
            authors.forEach { author ->
                jobs.add(async {
                    runCatching {
                        FollowSection(
                            type = "作者",
                            name = author.author,
                            comics = SourceManager.current()
                                .browse(page = 1, category = null, sort = ComicSort.DD, author = author.author)
                                .items.take(SECTION_LIMIT),
                        )
                    }.getOrNull()
                })
            }
            keywords.forEach { keyword ->
                jobs.add(async {
                    runCatching {
                        FollowSection(
                            type = "关键词",
                            name = keyword,
                            comics = SourceManager.current()
                                .search(keyword = keyword, page = 1, sort = ComicSort.DD)
                                .items.take(SECTION_LIMIT),
                        )
                    }.getOrNull()
                })
            }
            categories.forEach { category ->
                jobs.add(async {
                    runCatching {
                        FollowSection(
                            type = "分类",
                            name = category.title,
                            comics = SourceManager.current()
                                .browse(page = 1, category = category.id, sort = ComicSort.DD)
                                .items.take(SECTION_LIMIT),
                        )
                    }.getOrNull()
                })
            }
            jobs.mapNotNull { it.await() }
                .filter { it.comics.isNotEmpty() }
        }
    }
}