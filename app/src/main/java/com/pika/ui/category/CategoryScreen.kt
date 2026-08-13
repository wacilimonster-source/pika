package com.pika.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
 * 支持拖拽排序和显示/隐藏设置，本地持久化保存。
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
    var showSettings by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(com.pika.data.CategorySettings.get()) }

    LaunchedEffect(activeSource) {
        viewModel.loadCategories()
    }

    val displayCategories = remember(categories, settings) {
        val filtered = categories.filter { it.id !in settings.hidden }
        if (settings.order.isNotEmpty()) {
            val orderMap = settings.order.withIndex().associate { (i, id) -> id to i }
            filtered.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
        } else {
            filtered
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分类") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        if (displayCategories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (categories.isEmpty()) "当前源暂无分类" else "所有分类已隐藏",
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
                items(displayCategories, key = { it.id }) { category ->
                    CategoryCard(category = category, onClick = { onCategoryClick(category.id) })
                }
            }
        }
        if (showSettings) {
            CategoryReorderDialog(
                categories = categories,
                currentSettings = settings,
                onSettingsChange = { newSettings ->
                    settings = newSettings
                    com.pika.data.CategorySettings.save(newSettings)
                },
                onDismiss = { showSettings = false },
            )
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
    val totalPages by viewModel.totalPages.collectAsState()
    val listState = rememberLazyGridState()
    val categoryTitle = categories.firstOrNull { it.id == categoryId }?.title ?: "分类"
    val activeSource by SourceManager.activeSource.collectAsState()
    val supportedSorts = remember(activeSource) { SourceManager.current().supportedSorts }
    var showFilter by remember { mutableStateOf(false) }
    var authorInput by remember { mutableStateOf("") }
    var tagInput by remember { mutableStateOf("") }

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
                actions = {
                    IconButton(onClick = { showFilter = !showFilter }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "筛选")
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
            // 高级筛选（作者）
            if (showFilter) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = authorInput,
                            onValueChange = { authorInput = it },
                            singleLine = true,
                            label = { Text("作者") },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = tagInput,
                            onValueChange = { tagInput = it },
                            singleLine = true,
                            label = { Text("标签") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = {
                            viewModel.setAuthor(authorInput.trim().ifBlank { null })
                            viewModel.setTag(tagInput.trim().ifBlank { null })
                        },
                        label = { Text("应用筛选") },
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
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                )
                com.pika.ui.browse.PaginationBar(
                    currentPage = viewModel.currentPage,
                    totalPages = totalPages,
                    onPageChange = { viewModel.jumpToPage(it) },
                    modifier = Modifier.padding(bottom = 8.dp),
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
                    .height(80.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (!category.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = category.coverUrl,
                        contentDescription = category.title,
                        contentScale = ContentScale.Crop,
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
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 4.dp),
            )
        }
    }
}
