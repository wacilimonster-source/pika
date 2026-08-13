package com.pika.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pika.core.model.ComicCategory
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicStatus
import com.pika.core.source.SourceManager
import com.pika.ui.browse.BrowseViewModel
import com.pika.ui.browse.ComicGridView

/**
 * 分类 Tab：以网格展示当前源的全部分类。
 * 点击分类进入该分类的漫画流（[CategoryComicsScreen]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    onCategoryClick: (String) -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: BrowseViewModel = viewModel(),
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    val categories by viewModel.categories.collectAsState()

    LaunchedEffect(activeSource) {
        viewModel.loadCategories()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("分类") }) },
    ) { innerPadding ->
        if (categories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "当前源暂无分类",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(categories, key = { it.id }) { category ->
                    CategoryCard(category = category, onClick = { onCategoryClick(category.id) })
                }
            }
        }
    }
}

/** 分类漫画流：固定一个分类，分页浏览，支持排序 + 连载状态筛选。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryComicsScreen(
    categoryId: String,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    viewModel: BrowseViewModel = viewModel(),
) {
    val categories by viewModel.categories.collectAsState()
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val status by viewModel.status.collectAsState()
    val listState = rememberLazyGridState()
    val categoryTitle = categories.firstOrNull { it.id == categoryId }?.title ?: "分类"
    val activeSource by SourceManager.activeSource.collectAsState()
    val supportedSorts = remember(activeSource) { SourceManager.current().supportedSorts }

    LaunchedEffect(categoryId, activeSource) {
        viewModel.loadCategories()
        viewModel.loadComics(page = 1, category = categoryId)
    }

    // 当前源不支持当前排序时回退到默认
    LaunchedEffect(activeSource, supportedSorts) {
        if (viewModel.sort.value !in supportedSorts) {
            viewModel.setSort(supportedSorts.first())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(categoryTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 排序
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
            // 连载状态筛选
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
                        text = "该分类下暂无符合条件的漫画",
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
                    onLoadMore = { viewModel.loadComics(page = viewModel.currentPage + 1, category = categoryId) },
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CategoryCard(category: ComicCategory, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!category.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = category.coverUrl,
                        contentDescription = category.title,
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = category.title.take(1),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = category.title,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            )
        }
    }
}
