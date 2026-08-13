package com.pika.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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

/** 搜索页：输入框 + 排序筛选 + 高级筛选（可滚动）+ 结果网格 */
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
    val categories by viewModel.categories.collectAsState()
    val showAdvanced by viewModel.showAdvanced.collectAsState()
    var input by remember { mutableStateOf("") }
    var authorInput by remember { mutableStateOf("") }
    var teamInput by remember { mutableStateOf("") }
    var uploaderInput by remember { mutableStateOf("") }
    var tagInput by remember { mutableStateOf("") }
    val listState = rememberLazyGridState()

    LaunchedEffect(Unit) { viewModel.loadCategories() }

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
                                    input = ""
                                    authorInput = ""
                                    teamInput = ""
                                    uploaderInput = ""
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
                    placeholder = { Text("搜索漫画 / 作者 / 标签") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
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
                            selected = viewModel.sort == ComicSort.DD,
                            onClick = { viewModel.updateFilter(sort = ComicSort.DD) },
                            label = { Text("新到旧") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = viewModel.sort == ComicSort.DA,
                            onClick = { viewModel.updateFilter(sort = ComicSort.DA) },
                            label = { Text("旧到新") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = viewModel.sort == ComicSort.LD,
                            onClick = { viewModel.updateFilter(sort = ComicSort.LD) },
                            label = { Text("最多喜欢") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = viewModel.sort == ComicSort.VD,
                            onClick = { viewModel.updateFilter(sort = ComicSort.VD) },
                            label = { Text("最多观看") },
                        )
                    }
                    item {
                        FilterChip(
                            selected = showAdvanced,
                            onClick = { viewModel.toggleAdvanced() },
                            label = { Text(if (showAdvanced) "收起筛选" else "更多筛选") },
                        )
                    }
                }
                if (showAdvanced) {
                    AdvancedFilterPanel(
                        categories = categories,
                        selectedCategoryIds = viewModel.categoryIds,
                        authorInput = authorInput,
                        teamInput = teamInput,
                        uploaderInput = uploaderInput,
                        tagInput = tagInput,
                        onCategoryToggle = { id ->
                            val current = viewModel.categoryIds
                            viewModel.updateFilter(
                                categoryIds = if (id in current) current - id else current + id,
                            )
                        },
                        onAuthorChange = { authorInput = it },
                        onTeamChange = { teamInput = it },
                        onUploaderChange = { uploaderInput = it },
                        onTagChange = { tagInput = it },
                        onApply = {
                            viewModel.updateFilter(
                                author = authorInput.trim().ifBlank { null },
                                chineseTeam = teamInput.trim().ifBlank { null },
                                uploader = uploaderInput.trim().ifBlank { null },
                                tags = tagInput.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet(),
                            )
                        },
                    )
                }
                if (comics.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = when {
                                loading -> "搜索中..."
                                keyword.isBlank() -> "输入关键词开始搜索"
                                else -> "没有更多结果"
                            },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedFilterPanel(
    categories: List<com.pika.core.model.ComicCategory>,
    selectedCategoryIds: Set<String>,
    authorInput: String,
    teamInput: String,
    uploaderInput: String,
    tagInput: String,
    onCategoryToggle: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onTeamChange: (String) -> Unit,
    onUploaderChange: (String) -> Unit,
    onTagChange: (String) -> Unit,
    onApply: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        if (categories.isNotEmpty()) {
            Text(
                text = "分类（可多选）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                categories.forEach { category ->
                    FilterChip(
                        selected = category.id in selectedCategoryIds,
                        onClick = { onCategoryToggle(category.id) },
                        label = { Text(category.title) },
                    )
                }
            }
        }
        SmallFilterField("作者", authorInput, onAuthorChange)
        SmallFilterField("汉化组", teamInput, onTeamChange)
        SmallFilterField("上传者", uploaderInput, onUploaderChange)
        SmallFilterField("标签（逗号分隔）", tagInput, onTagChange)
        FilterChip(
            selected = false,
            onClick = onApply,
            label = { Text("应用筛选") },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SmallFilterField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}
