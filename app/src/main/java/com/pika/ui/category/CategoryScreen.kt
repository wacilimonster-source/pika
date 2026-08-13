package com.pika.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.AlertDialog
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
    val dateRange by viewModel.dateRange.collectAsState()
    val listState = rememberLazyGridState()
    val categoryTitle = categories.firstOrNull { it.id == categoryId }?.title ?: "分类"
    val activeSource by SourceManager.activeSource.collectAsState()
    val supportedSorts = remember(activeSource) { SourceManager.current().supportedSorts }
    var showDateDialog by remember { mutableStateOf(false) }
    var showDisplaySettings by remember { mutableStateOf(false) }

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
                    IconButton(onClick = { showDisplaySettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
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
            // 更新日期范围筛选
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = dateRange != null,
                    onClick = { showDateDialog = true },
                    label = { Text(if (dateRange != null) dateRange!!.label() else "日期范围") },
                )
                if (dateRange != null) {
                    Text(
                        text = "按更新时间筛选（需联网翻页加载）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (showDateDialog) {
                DateRangeDialog(
                    current = dateRange,
                    onConfirm = { range ->
                        viewModel.setDateRange(range)
                        showDateDialog = false
                    },
                    onClear = {
                        viewModel.setDateRange(null)
                        showDateDialog = false
                    },
                    onDismiss = { showDateDialog = false },
                )
            }
            if (showDisplaySettings) {
                DisplaySettingsDialog(
                    currentSort = sort,
                    supportedSorts = supportedSorts,
                    onSortChange = { viewModel.setSort(it) },
                    onDismiss = { showDisplaySettings = false },
                )
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

/** 更新日期范围选择：年份 + 起止月份。服务端无日期查询，实际按客户端过滤。 */
@Composable
private fun DateRangeDialog(
    current: com.pika.core.model.ComicDateRange?,
    onConfirm: (com.pika.core.model.ComicDateRange) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val init = current ?: com.pika.core.model.ComicDateRange(
        fromYear = currentYear - 1,
        fromMonth = 1,
        toYear = currentYear,
        toMonth = 12,
    )
    var fromYear by remember { mutableStateOf(init.fromYear) }
    var fromMonth by remember { mutableStateOf(init.fromMonth) }
    var toYear by remember { mutableStateOf(init.toYear) }
    var toMonth by remember { mutableStateOf(init.toMonth) }
    val minYear = 2010
    val valid = fromYear < toYear || (fromYear == toYear && fromMonth <= toMonth)

    @Composable
    fun YearPicker(label: String, year: Int, onChange: (Int) -> Unit) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
            IconButton(onClick = { onChange((year - 1).coerceAtLeast(minYear)) }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "上一年")
            }
            Text(
                text = "$year 年",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            IconButton(onClick = { onChange((year + 1).coerceAtMost(currentYear)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一年")
            }
        }
    }

    @Composable
    fun MonthRow(label: String, month: Int, onChange: (Int) -> Unit) {
        Column {
            Text(
                text = "$label（${(if (label == "从") fromYear else toYear)} 年）",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items((1..12).toList(), key = { it }) { m ->
                    FilterChip(
                        selected = month == m,
                        onClick = { onChange(m) },
                        label = { Text("${m}月") },
                    )
                }
            }
        }
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更新日期范围") },
        text = {
            Column {
                Text(
                    text = "按更新时间过滤已加载的漫画（需联网自动翻页，较早数据可能覆盖不全）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                YearPicker("起始", fromYear) { fromYear = it }
                MonthRow("从", fromMonth) { fromMonth = it }
                Spacer(Modifier.height(6.dp))
                YearPicker("结束", toYear) { toYear = it }
                MonthRow("到", toMonth) { toMonth = it }
                if (!valid) {
                    Text(
                        text = "起始不能晚于结束",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onConfirm(
                        com.pika.core.model.ComicDateRange(
                            fromYear = fromYear,
                            fromMonth = fromMonth,
                            toYear = toYear,
                            toMonth = toMonth,
                        )
                    )
                },
                enabled = valid,
            ) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    androidx.compose.material3.TextButton(onClick = onClear) { Text("清除") }
                }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 显示设置弹窗：排序方式 */
@Composable
private fun DisplaySettingsDialog(
    currentSort: ComicSort,
    supportedSorts: List<ComicSort>,
    onSortChange: (ComicSort) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("显示设置") },
        text = {
            Column {
                Text("排序方式", style = MaterialTheme.typography.labelMedium)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(vertical = 4.dp),
                ) {
                    items(supportedSorts, key = { it.name }) { s ->
                        FilterChip(
                            selected = currentSort == s,
                            onClick = { onSortChange(s) },
                            label = { Text(s.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
