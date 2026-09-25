package com.pika.ui.favourite

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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

    /** 请求代际：连点页码时旧页响应不得覆盖新页（与 ComicDetailViewModel 的 gen 范式一致） */
    private var loadGeneration = 0

    fun jumpToPage(page: Int) {
        needsRestore = false
        val gen = ++loadGeneration
        _currentPage.value = page
        _endReached.value = true  // 防止加载期间 ComicGridView 触发 loadMore
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val r = SourceManager.current().favourites(page)
                if (gen != loadGeneration) return@launch
                _comics.value = r.items  // 替换而非追加
                _totalPages.value = r.pages.coerceAtLeast(1)
                _endReached.value = page >= r.pages
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != loadGeneration) return@launch
                // 此前只在列表为空时提示，翻页失败完全静默——改为始终可感知
                _error.value = e.message ?: "加载失败"
            } finally {
                if (gen == loadGeneration) _loading.value = false
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
        val gen = ++loadGeneration
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val result = SourceManager.current().favourites(page)
                if (gen != loadGeneration) return@launch
                _comics.value = if (page == 1) result.items else _comics.value + result.items
                _totalPages.value = result.pages.coerceAtLeast(1)
                _endReached.value = page >= result.pages
                _currentPage.value = page
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != loadGeneration) return@launch
                if (page == 1 && _comics.value.isEmpty()) {
                    _error.value = e.message ?: "加载失败"
                }
            } finally {
                if (gen == loadGeneration) _loading.value = false
            }
        }
    }

    /**
     * 返回恢复专用：回填页码指示（滚动位置由 Navigation 恢复）。
     * 若收藏数据在别处被变更过（详情页收藏/取消收藏成功），后台静默刷新当前页：
     * 走 jumpToPage 的替换式加载，列表按索引重排，滚动位置保持不变。
     * 此前返回后整个生命周期不再请求，取消收藏的条目残留、新收藏不可见。
     */
    fun consumeRestoreAndRefreshIfNeeded() {
        if (!needsRestore) return
        needsRestore = false
        _currentPage.value = _savedCurrentPage
        if (com.pika.data.FavouriteSync.dirty) {
            com.pika.data.FavouriteSync.dirty = false
            jumpToPage(_savedCurrentPage)
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
    // 长按取消收藏：待确认条目（null = 无）
    var pendingUnfavourite by remember { mutableStateOf<ComicSummary?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 保存滚动位置（每次 Activity 暂停时都保存，覆盖所有导航场景）
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
        viewModel.saveScrollState(listState.firstVisibleItemIndex, currentPage)
    }
    // 恢复滚动状态（导航返回后首次 recompose 时执行）
    LaunchedEffect(Unit) {
        val restored = viewModel.needsRestore
        // 恢复走专用通道；若收藏数据在详情页被变更过，后台静默刷新当前页
        viewModel.consumeRestoreAndRefreshIfNeeded()
        if (!restored) {
            viewModel.load(page = 1)
        }
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("我的收藏") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets(0, 0),
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
                    // 长按快捷取消收藏（免进详情点心形），带确认
                    onComicLongClick = { ref ->
                        comics.firstOrNull { it.ref == ref }?.let { pendingUnfavourite = it }
                    },
                )
                if (totalPages > 1) {
                    PaginationBar(
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onPageChange = { p ->
                            viewModel.jumpToPage(p)
                            // 服务端换页：回顶展示新页（返回定位不受影响，恢复只在导航返回重组时执行）
                            listState.requestScrollToItem(0)
                        },
                    )
                }
            }
        }
    }

    pendingUnfavourite?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingUnfavourite = null },
            title = { Text("取消收藏") },
            text = { Text("不再收藏《${target.title}》？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingUnfavourite = null
                    scope.launch {
                        val result = runCatching {
                            SourceManager.current().favourite(target.id, false)
                        }
                        result.fold(
                            onSuccess = { favourited ->
                                if (favourited == false) {
                                    viewModel.jumpToPage(currentPage)
                                    snackbarHostState.showSnackbar("已取消收藏《${target.title}》")
                                } else {
                                    snackbarHostState.showSnackbar("操作未生效，请稍后重试")
                                }
                            },
                            onFailure = { e ->
                                snackbarHostState.showSnackbar("取消收藏失败：${e.message ?: "未知错误"}")
                            },
                        )
                    }
                }) { Text("取消收藏") }
            },
            dismissButton = {
                TextButton(onClick = { pendingUnfavourite = null }) { Text("保留") }
            },
        )
    }
}
