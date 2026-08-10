package com.pika.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.source.SourceManager
import com.pika.ui.browse.BrowseViewModel
import com.pika.ui.browse.ComicGridView

/**
 * 首页：当前活动源的内容流。
 * 顶部分类 Tab（横向滚动），下方漫画网格（分页加载）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onComicClick: (String) -> Unit = {},
    viewModel: BrowseViewModel = viewModel(),
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val listState = rememberLazyGridState()
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeSource) {
        selectedCategory = null
        viewModel.loadCategories()
        viewModel.loadComics(page = 1)
    }

    LaunchedEffect(activeSource) {
        listState.scrollToItem(0)
    }

    Scaffold(
        topBar = {
            Column {
                Text(
                    text = "PiKA · ${activeSource.displayName}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(16.dp),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedCategory == null,
                            onClick = {
                                selectedCategory = null
                                viewModel.loadComics(page = 1)
                            },
                            label = { Text("全部") },
                        )
                    }
                    items(categories, key = { it.id }) { category ->
                        FilterChip(
                            selected = selectedCategory == category.id,
                            onClick = {
                                selectedCategory = category.id
                                viewModel.loadComics(page = 1, category = category.id)
                            },
                            label = { Text(category.title) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        if (error != null && comics.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = error ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            ComicGridView(
                comics = comics,
                loading = loading,
                endReached = endReached,
                listState = listState,
                onLoadMore = { viewModel.loadComics(page = viewModel.currentPage + 1) },
                onComicClick = onComicClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}