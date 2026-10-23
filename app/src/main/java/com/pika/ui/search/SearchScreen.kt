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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.model.ComicSort
import com.pika.ui.browse.ComicGridView
import com.pika.ui.browse.filterByRead

/** 搜索页：输入框（含返回/重置/标签筛选）+ 排序筛选 + 结果网格 + 底部页码分页 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onBack: (() -> Unit)? = null,
    onComicClick: (String) -> Unit = {},
    initialKeyword: String? = null,
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
    val searchError by viewModel.searchError.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val selectedTag by viewModel.selectedTag.collectAsState()
    val history by viewModel.history.collectAsState()
    val hotWords by viewModel.hotWords.collectAsState()
    // 输入框初值取 VM 当前关键词：从详情返回重组时恢复显示，避免与结果列表不一致
    var input by remember { mutableStateOf(viewModel.keyword.value) }
    var showTagSheet by remember { mutableStateOf(false) }
    var readFilterName by rememberSaveable { mutableStateOf(com.pika.ui.browse.ReadFilter.ALL.name) }
    val readFilter = com.pika.ui.browse.ReadFilter.valueOf(readFilterName)
    // 筛选模式下的客户端翻页（累积数据按 20 条一页切片，纯本地不请求）
    var filterPage by rememberSaveable { mutableStateOf(1) }
    val listState = rememberLazyGridState()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // 筛选激活时后台自动拉取剩余分页（每页加载完自动触发下一页，串行不并发）
    LaunchedEffect(readFilter, comics, loading, endReached) {
        if (readFilter != com.pika.ui.browse.ReadFilter.ALL && !loading && !endReached && comics.isNotEmpty()) {
            viewModel.loadMore()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadHotWords()
        viewModel.loadHistory()
        viewModel.loadTags()
    }

    // 新搜索统一入口：任何会重置为第 1 页的搜索都必须同步归位 filterPage（双侧页码轨）。
    // 历史词/热搜词/搜索框/标签筛选四个入口此前不归位，筛选模式下页码与内容错位。
    fun beginSearch(kw: String) {
        filterPage = 1
        viewModel.search(kw, page = 1)
    }

    // 从详情页标签点击进入：自动填入关键词并立即搜索（每次导航都是新实例，只执行一次；
    // 已搜索过则跳过，避免从详情返回重组时重新搜索重置结果与滚动状态）
    LaunchedEffect(initialKeyword) {
        val kw = initialKeyword?.trim().orEmpty()
        if (kw.isNotEmpty() && !viewModel.hasSearched) {
            input = kw
            focusManager.clearFocus()
            beginSearch(kw)
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
                beginSearch(input.trim())
            }),
            leadingIcon = {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (tags.isNotEmpty()) {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            showTagSheet = true
                        }) {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = "标签筛选",
                                tint = if (selectedTag != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                    if (input.isNotBlank() || keyword.isNotBlank()) {
                        IconButton(onClick = {
                            focusManager.clearFocus()
                            input = ""
                            viewModel.resetAll()
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "重置")
                        }
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // 排序切换：VM 回第 1 页后，UI 侧页码指示必须同步归位并回顶——
        // 否则分页条停在旧页码（筛选模式下旧页切片还会短暂显示空列表）
        val onSortChange: (ComicSort) -> Unit = { sort ->
            viewModel.updateSortOnly(sort)
            filterPage = 1
            listState.requestScrollToItem(0)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                FilterChip(
                    selected = currentSort == ComicSort.DD,
                    onClick = { onSortChange(ComicSort.DD) },
                    label = { Text("新到旧") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.DA,
                    onClick = { onSortChange(ComicSort.DA) },
                    label = { Text("旧到新") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.LD,
                    onClick = { onSortChange(ComicSort.LD) },
                    label = { Text("最多喜欢") },
                )
            }
            item {
                FilterChip(
                    selected = currentSort == ComicSort.VD,
                    onClick = { onSortChange(ComicSort.VD) },
                    label = { Text("最多观看") },
                )
            }
            com.pika.ui.browse.readFilterOptions.forEach { (f, label) ->
                item {
                    FilterChip(
                        selected = readFilter == f,
                        onClick = {
                            readFilterName = f.name
                            viewModel.resetFilterPage()
                            filterPage = 1
                            listState.requestScrollToItem(0)
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
        // 筛选结果（只随筛选切换/分页加载更新；阅读状态变化仅刷新角标，不实时过滤）
        // 筛选模式：累积数据按服务端页（20 条）切片 + 过滤，页码可点（客户端翻页）
        val displayComics = remember(readFilter, comics, filterPage) {
            if (readFilter == com.pika.ui.browse.ReadFilter.ALL) {
                comics
            } else {
                comics.drop((filterPage - 1) * 20).take(20).filterByRead(readFilter)
            }
        }
        // 分页条回调（筛选模式=客户端切片换页；普通模式=服务端换页），正常/空态两个分支共用
        val onBarPageChange: (Int) -> Unit = if (readFilter != com.pika.ui.browse.ReadFilter.ALL) {
            { p ->
                filterPage = p
                // 客户端切片换页：数据整体替换后网格会按索引保留位置，需显式回顶
                listState.requestScrollToItem(0)
            }
        } else {
            { p ->
                // 同步分页条高亮与箭头目标（此前普通模式 filterPage 不更新导致卡在旧值）
                filterPage = p
                viewModel.jumpToPage(p)
                // jumpToPage 内部已有 shouldScrollToTop 兜底；这里立即回顶避免旧列表位置残留
                listState.requestScrollToItem(0)
            }
        }
        // 回顶保障（两段式之第 2 段，必须在 displayComics 定义之后）：
        // 未兑现的回顶请求在"当前展示内容"变化后的首个测量点强制执行。
        // 此前是"请求一次、尽力而为"——单词搜索加载中会清空列表（网格整体卸载），
        // requestScrollToItem 与挂起 scrollToItem 都只能等"下一次恰好发生的测量"，
        // 数据迟到时没有落点保证，表现为"翻页后停在旧位置"。
        // 兑现只跟 tick 走：displayComics 参与 key 只提供"内容落地后补执行"的时机，
        // 已兑现的请求不会被后续无关内容变化重复执行（筛选补页链追加数据不会把用户拉顶）。
        // appliedTopTick 初值同步当前 tick：从详情返回时 VM 里的 tick 已兑现过，不得再拉顶。
        var appliedTopTick by remember { mutableStateOf(viewModel.shouldScrollToTop.value) }
        LaunchedEffect(shouldScrollToTop, displayComics) {
            if (shouldScrollToTop > appliedTopTick) {
                listState.scrollToItem(0)
                appliedTopTick = shouldScrollToTop
            }
        }
        if (displayComics.isEmpty()) {
            // 空态也保留分页条（有多页时，如筛选切片为空），避免无路可翻
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (keyword.isBlank() && !loading) {
                    // 未输入关键词：展示搜索历史 + 热搜词（热搜本就在拉取，此前未渲染）
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (history.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "搜索历史",
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "清空",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clickable { viewModel.clearHistory() }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 8.dp),
                            ) {
                                history.forEach { h ->
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            input = h
                                            focusManager.clearFocus()
                                            listState.requestScrollToItem(0)
                                            beginSearch(h)
                                        },
                                        label = { Text(h, maxLines = 1) },
                                    )
                                }
                            }
                        }
                        if (hotWords.isNotEmpty()) {
                            Text(
                                text = "热门搜索",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 20.dp),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                            ) {
                                hotWords.forEachIndexed { index, word ->
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            input = word
                                            focusManager.clearFocus()
                                            listState.requestScrollToItem(0)
                                            beginSearch(word)
                                        },
                                        label = { Text(if (index < 3) "${index + 1}  $word" else word, maxLines = 1) },
                                    )
                                }
                            }
                        }
                    }
                } else if (loading) {
                    // A2 骨架屏：搜索首刷替代「搜索中...」文字，列数跟随网格设置
                    com.pika.ui.common.ComicGridSkeleton(
                        columns = com.pika.data.GridSettings.columnsFlow.collectAsState().value,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = when {
                                searchError != null -> "搜索失败：$searchError"
                                readFilter != com.pika.ui.browse.ReadFilter.ALL -> "没有符合条件的作品"
                                else -> "没有更多结果"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // 单词搜索此前失败会直接闪退；现在显示错误并可一键重试
                        if (searchError != null && !loading && keyword.isNotBlank()) {
                            Text(
                                text = "点击重试",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .clickable { beginSearch(keyword) },
                            )
                        }
                    }
                }
            }
            if (totalPages > 1) {
                com.pika.ui.browse.PaginationBar(
                    currentPage = filterPage,
                    totalPages = totalPages,
                    loadedPages = (comics.size + 19) / 20,
                    onPageChange = onBarPageChange,
                    progressMode = readFilter != com.pika.ui.browse.ReadFilter.ALL,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                ComicGridView(
                    comics = displayComics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,
                    // 纯分页设计：滚动不追加；筛选模式的自动补页由上方 LaunchedEffect 后台链驱动
                    onLoadMore = {},
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                    showTailLoading = readFilter == com.pika.ui.browse.ReadFilter.ALL,
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
                    currentPage = filterPage,
                    totalPages = totalPages,
                    loadedPages = (comics.size + 19) / 20,
                    onPageChange = onBarPageChange,
                    progressMode = readFilter != com.pika.ui.browse.ReadFilter.ALL,
                )
            }
        }
    }

    if (showTagSheet && tags.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showTagSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
            ) {
                Text(
                    text = "标签筛选",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "与关键词为「且」关系，单选标签",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    FilterChip(
                        selected = selectedTag == null,
                        onClick = {
                            showTagSheet = false
                            filterPage = 1
                            viewModel.selectTag(null)
                        },
                        label = { Text("全部") },
                    )
                    tags.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = {
                                showTagSheet = false
                                filterPage = 1
                                viewModel.selectTag(tag)
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }
    }
}
