package com.pika.ui.favourite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import com.pika.ui.browse.ComicGridView
import com.pika.ui.browse.PaginationBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 收藏列表 VM（当前源） */
class FavouriteViewModel : ViewModel() {
    private val _comics = MutableStateFlow<List<ComicSummary>>(emptyList())
    val comics: StateFlow<List<ComicSummary>> = _comics
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading
    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage

    private var _savedFirstVisibleIndex: Int = 0
    val savedFirstVisibleIndex: Int get() = _savedFirstVisibleIndex

    private var _savedCurrentPage: Int = 1
    val savedCurrentPage: Int get() = _savedCurrentPage

    /** 是否需要恢复滚动状态（导航返回时为 true） */
    var needsRestore: Boolean = false
        private set

    fun saveScrollState(firstVisibleIndex: Int, currentPage: Int) {
        _savedFirstVisibleIndex = firstVisibleIndex
        _savedCurrentPage = currentPage
        needsRestore = true
    }

    fun jumpToPage(page: Int) {
        needsRestore = false
        _currentPage.value = page
        _endReached.value = true  // 防止加载期间 ComicGridView 触发 loadMore
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val r = SourceManager.current().favourites(page)
                _comics.value = r.items  // 替换而非追加
                _totalPages.value = r.pages.coerceAtLeast(1)
                _endReached.value = page >= r.pages
            } catch (e: Exception) {
                if (_comics.value.isEmpty()) {
                    _error.value = e.message ?: "加载失败"
                }
            } finally {
                _loading.value = false
            }
        }
    }

    fun load(page: Int) {
        if (_loading.value) return
        if (needsRestore) {
            needsRestore = false
            _currentPage.value = _savedCurrentPage
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val result = SourceManager.current().favourites(page)
                _comics.value = if (page == 1) result.items else _comics.value + result.items
                _totalPages.value = result.pages.coerceAtLeast(1)
                _endReached.value = page >= result.pages
                _currentPage.value = page
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

/** 我的收藏页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavouriteScreen(
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: FavouriteViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val listState = rememberLazyGridState()

    // 保存滚动位置（每次 Activity 暂停时都保存，覆盖所有导航场景）
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
        viewModel.saveScrollState(listState.firstVisibleItemIndex, currentPage)
    }
    // 恢复滚动状态（导航返回后首次 recompose 时执行）
    LaunchedEffect(Unit) {
        // needsRestore 为 true 时 load() 会跳过并恢复页码
        viewModel.load(page = 1)
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的收藏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (error != null && comics.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = error ?: "加载失败", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ComicGridView(
                    comics = comics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                )
                if (totalPages > 1) {
                    PaginationBar(
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageChange = { viewModel.jumpToPage(it) },
                    )
                }
            }
        }
    }
}
