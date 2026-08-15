package com.pika.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.model.ComicSort
import com.pika.ui.browse.ComicGridView

/** 搜索页：输入框（含返回/重置）+ 排序筛选 + 标签筛选按钮 + 结果网格 + 底部页码分页 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onBack: (() -> Unit)? = null,
    onComicClick: (String) -> Unit = {},
    viewModel: SearchViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val keyword by viewModel.keyword.collectAsState()
    val currentSort by viewModel.sort.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val multiLoading by viewModel.multiLoading.collectAsState()
    val shouldScrollToTop by viewModel.shouldScrollToTop.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyGridState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // 翻页时滚到顶部
    LaunchedEffect(shouldScrollToTop) {
        if (shouldScrollToTop > 0) {
            listState.scrollToItem(0)
        }
    }

    // 保存滚动位置（页面不可见时，如导航到详情）
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollState(listState.firstVisibleItemIndex, viewModel.currentPage.value)
        }
    }
    // 恢复滚动位置（首次组成为 false，导航返回后为 true）
    LaunchedEffect(viewModel.isScrollStateRestored) {
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
            viewModel.markScrollStateRestored()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize(),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            placeholder = { Text("搜索漫画，多个关键词用空格分隔") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focusManager.clearFocus()
                viewModel.search(input.trim(), page = 1)
            }),
            leadingIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            trailingIcon = {
                if (input.isNotBlank() || keyword.isNotBlank()) {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        input = ""
                        viewModel.resetAll()
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "重置")
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = currentSort == ComicSort.DD,
                    onClick = { viewModel.updateSortOnly(ComicSort.DD) },
                    label = { Text("新到旧") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.DA,
                    onClick = { viewModel.updateSortOnly(ComicSort.DA) },
                    label = { Text("旧到新") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.LD,
                    onClick = { viewModel.updateSortOnly(ComicSort.LD) },
                    label = { Text("最多喜欢") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.VD,
                    onClick = { viewModel.updateSortOnly(ComicSort.VD) },
                    label = { Text("最多观看") },
                )
            }
        }
        if (comics.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = when {
                        loading -> "搜索中..."
                        keyword.isBlank() -> "输入关键词开始搜索（多个关键词用空格分隔）"
                        else -> "没有更多结果"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ComicGridView(
                    comics = comics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,
                    onLoadMore = { viewModel.loadMore() },
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                )
                if (multiLoading) {
                    Text(
                        text = "后台仍在加载更多结果...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                com.pika.ui.browse.PaginationBar(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onPageChange = { viewModel.jumpToPage(it) },
                )
            }
        }
    }
}
