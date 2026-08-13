package com.pika.ui.search

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.ui.browse.ComicGridView

/** 搜索页：输入框 + 热搜词 + 结果网格 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    /** 传入则显示顶栏返回按钮；作为底部 Tab 时传 null 隐藏。 */
    onBack: (() -> Unit)? = null,
    onComicClick: (String) -> Unit = {},
    viewModel: SearchViewModel = viewModel(),
) {
    val hotWords by viewModel.hotWords.collectAsState()
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val keyword by viewModel.keyword.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyGridState()

    LaunchedEffect(Unit) { viewModel.loadHotWords() }

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
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding)) {
            Column(Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("搜索漫画 / 作者 / 标签") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        viewModel.search(input.trim(), page = 1)
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (comics.isEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(hotWords) { word ->
                            SuggestionChip(
                                onClick = {
                                    input = word
                                    viewModel.search(word, page = 1)
                                },
                                label = { Text(word) },
                            )
                        }
                    }
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        Text(
                            text = if (keyword.isBlank()) "输入关键词开始搜索" else "没有更多结果",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    ComicGridView(
                        comics = comics,
                        loading = loading,
                        endReached = endReached,
                        listState = listState,
                        onLoadMore = { viewModel.search(keyword, page = viewModel.currentPage + 1) },
                        onComicClick = onComicClick,
                    )
                }
            }
        }
    }
}