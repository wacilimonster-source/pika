package com.pika.ui.favourite

import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import com.pika.ui.browse.ComicGridView
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

    var currentPage: Int = 1
        private set

    private var _savedFirstVisibleIndex: Int = 0
    val savedFirstVisibleIndex: Int get() = _savedFirstVisibleIndex

    private var _savedCurrentPage: Int = 1
    val savedCurrentPage: Int get() = _savedCurrentPage

    var isScrollStateRestored: Boolean = false
        private set

    fun saveScrollState(firstVisibleIndex: Int, currentPage: Int) {
        _savedFirstVisibleIndex = firstVisibleIndex
        _savedCurrentPage = currentPage
    }

    fun markScrollStateRestored() {
        isScrollStateRestored = true
    }

    fun load(page: Int) {
        if (_loading.value) return
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val result = SourceManager.current().favourites(page)
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
    val listState = rememberLazyGridState()

    // 保存滚动位置
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollState(listState.firstVisibleItemIndex, viewModel.currentPage)
        }
    }
    // 恢复滚动位置
    LaunchedEffect(viewModel.isScrollStateRestored) {
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
            viewModel.markScrollStateRestored()
        }
    }

    LaunchedEffect(Unit) { viewModel.load(page = 1) }

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
            ComicGridView(
                comics = comics,
                loading = loading,
                endReached = endReached,
                listState = listState,
                onLoadMore = { viewModel.load(page = viewModel.currentPage + 1) },
                onComicClick = onComicClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
