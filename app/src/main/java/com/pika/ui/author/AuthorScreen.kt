package com.pika.ui.author

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicStatus
import com.pika.core.source.SourceManager
import com.pika.ui.browse.ComicGridView
import com.pika.ui.browse.PaginationBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorComicsScreen(
    author: String,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: AuthorViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val status by viewModel.status.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val listState = rememberLazyGridState()
    val activeSource by SourceManager.activeSource.collectAsState()
    val supportedSorts = remember(activeSource) { SourceManager.current().supportedSorts }

    // 保存滚动位置（导航离开时，如进入详情页）
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollState(listState.firstVisibleItemIndex, viewModel.currentPage)
        }
    }
    // 恢复滚动位置（导航返回时）
    LaunchedEffect(viewModel.needsScrollRestore) {
        if (viewModel.needsScrollRestore && viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
        }
    }

    LaunchedEffect(author, activeSource) {
        viewModel.loadComics(author, page = 1)
    }

    LaunchedEffect(activeSource, supportedSorts) {
        if (viewModel.sort.value !in supportedSorts) {
            viewModel.setSort(supportedSorts.first())
        }
    }

    val unsupported = activeSource == com.pika.core.source.SourceType.JMCOMIC

    var favourited by remember { mutableStateOf(com.pika.data.AuthorFavourites.contains(author)) }
    LaunchedEffect(author) {
        favourited = com.pika.data.AuthorFavourites.contains(author)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (favourited) {
                            com.pika.data.AuthorFavourites.remove(author)
                        } else {
                            com.pika.data.AuthorFavourites.add(
                                author = author,
                                coverUrl = comics.firstOrNull()?.coverUrl.orEmpty(),
                            )
                        }
                        favourited = !favourited
                    }) {
                        Icon(
                            imageVector = if (favourited) {
                                Icons.Filled.Favorite
                            } else {
                                Icons.Outlined.FavoriteBorder
                            },
                            contentDescription = if (favourited) "取消收藏作者" else "收藏作者",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (unsupported) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "当前源（禁漫）不支持按作者浏览作品",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(supportedSorts, key = { it.name }) { s ->
                    FilterChip(
                        selected = sort == s,
                        onClick = { viewModel.setSort(s) },
                        label = { Text(s.label) },
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ComicStatus.entries.toList(), key = { it.name }) { st ->
                    FilterChip(
                        selected = status == st,
                        onClick = { viewModel.setStatus(st) },
                        label = { Text(st.label) },
                    )
                }
            }
            if (error != null && comics.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = error ?: "加载失败",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else if (comics.isEmpty() && !loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "该作者暂无作品",
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
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                )
                PaginationBar(
                    currentPage = viewModel.currentPage,
                    totalPages = totalPages,
                    onPageChange = { viewModel.jumpToPage(it) },
                )
            }
        }
    }
}
