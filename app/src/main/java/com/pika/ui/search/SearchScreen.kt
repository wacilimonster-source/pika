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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

/** 搜索页：输入框 + 排序筛选 + 标签筛选 + 结果网格 + 底部页码分页 */
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
    val tags by viewModel.tags.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    var input by remember { mutableStateOf("") }
    var tagInput by remember { mutableStateOf("") }
    val listState = rememberLazyGridState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (comics.isNotEmpty() || keyword.isNotBlank()) {
                        Text(
                            text = "重置",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .clickable {
                                    focusManager.clearFocus()
                                    input = ""
                                    tagInput = ""
                                    viewModel.resetAll()
                                }
                                .padding(12.dp),
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("搜索漫画 / 标签") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        focusManager.clearFocus()
                        viewModel.search(input.trim(), page = 1)
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = currentSort == ComicSort.DD,
                            onClick = { viewModel.updateFilter(sort = ComicSort.DD) },
                            label = { Text("新到旧") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = currentSort == ComicSort.DA,
                            onClick = { viewModel.updateFilter(sort = ComicSort.DA) },
                            label = { Text("旧到新") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = currentSort == ComicSort.LD,
                            onClick = { viewModel.updateFilter(sort = ComicSort.LD) },
                            label = { Text("最多喜欢") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = currentSort == ComicSort.VD,
                            onClick = { viewModel.updateFilter(sort = ComicSort.VD) },
                            label = { Text("最多观看") },
                        )
                    }
                }
                // 标签筛选（可输入多个，逗号分隔）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        singleLine = true,
                        placeholder = { Text(if (tags.isEmpty()) "标签（逗号分隔，如：校园,热血）" else "标签：${tags.joinToString(",")}") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            focusManager.clearFocus()
                            applyTagFilter(tagInput, viewModel)
                        }),
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            focusManager.clearFocus()
                            applyTagFilter(tagInput, viewModel)
                        },
                        label = { Text("应用标签") },
                    )
                }
                if (comics.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = when {
                                loading -> "搜索中..."
                                keyword.isBlank() && tags.isEmpty() -> "输入关键词或标签开始搜索"
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
                            onLoadMore = {},
                            onComicClick = onComicClick,
                            modifier = Modifier.weight(1f),
                        )
                        com.pika.ui.browse.PaginationBar(
                            currentPage = viewModel.currentPage,
                            totalPages = totalPages,
                            onPageChange = { viewModel.search(keyword, page = it) },
                        )
                    }
                }
            }
        }
    }
}

private fun applyTagFilter(tagInput: String, viewModel: SearchViewModel) {
    viewModel.updateFilter(
        tags = tagInput.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
    )
}
