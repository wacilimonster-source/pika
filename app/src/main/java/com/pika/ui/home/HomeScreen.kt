package com.pika.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.source.SourceManager
import com.pika.ui.browse.BrowseViewModel
import com.pika.ui.browse.ComicGridView
import kotlinx.coroutines.launch

/**
 * 首页：更新横幅 + 继续阅读 + 当前源内容流（分类 Tab + 漫画网格）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onComicClick: (String) -> Unit = {},
    onResumeReading: (String, Int) -> Unit = { _, _ -> },
    onOpenRank: () -> Unit = {},
    viewModel: BrowseViewModel = viewModel(),
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val listState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    val updateInfo by com.pika.core.update.UpdateState.updateInfo.collectAsState()
    LaunchedEffect(Unit) { com.pika.core.update.UpdateState.checkOnce() }

    // 最近阅读（继续阅读）
    val recentReads = remember {
        mutableStateOf(com.pika.data.ReaderPrefs.current().recentReads())
    }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        recentReads.value = com.pika.data.ReaderPrefs.current().recentReads()
    }

    LaunchedEffect(activeSource) {
        selectedCategory = null
        viewModel.loadCategories()
        viewModel.loadComics(page = 1)
    }

    LaunchedEffect(activeSource) {
        listState.scrollToItem(0)
    }

    if (updateInfo != null && showUpdateDialog) {
        com.pika.ui.update.UpdateDialog(
            info = updateInfo!!,
            onDismiss = {
                showUpdateDialog = false
                com.pika.core.update.UpdateState.dismiss()
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                ) {
                    Text(
                        text = "PiKA · ${activeSource.displayName}",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "随便看看",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                scope.launch {
                                    try {
                                        val random = SourceManager.current().randomComics()
                                        random.firstOrNull()?.let { onComicClick(it.id) }
                                    } catch (e: Exception) {
                                        // 随机失败忽略
                                    }
                                }
                            }
                            .padding(4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "排行榜",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onOpenRank)
                            .padding(4.dp),
                    )
                }
                // 更新横幅
                if (updateInfo != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showUpdateDialog = true }
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "发现新版本 v${updateInfo!!.version}，点击更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // 继续阅读
                if (recentReads.value.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recentReads.value) { recent ->
                            ContinueReadCard(
                                recent = recent,
                                onClick = { onResumeReading(recent.comicId, recent.order) },
                            )
                        }
                    }
                }
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

/** 继续阅读卡片（最近阅读横向列表项） */
@Composable
private fun ContinueReadCard(
    recent: com.pika.data.RecentRead,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick),
        elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (recent.coverUrl.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = recent.coverUrl,
                        contentDescription = recent.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = recent.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            )
            Text(
                text = "第 ${recent.order} 话 · 续读",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 0.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
    }
}