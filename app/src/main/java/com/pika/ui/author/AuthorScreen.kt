package com.pika.ui.author

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.model.AuthorNameParser
import com.pika.core.model.AuthorSegment
import com.pika.core.model.ComicSort
import com.pika.core.model.ComicStatus
import com.pika.core.source.SourceManager
import com.pika.data.AuthorFavourites
import com.pika.ui.browse.ComicGridView
import com.pika.ui.browse.PaginationBar
import com.pika.ui.common.PikaFilterChip
import com.pika.ui.common.rememberClipboardCopier

/**
 * 作者作品页。
 *
 * [author] 是当前实际检索的署名片段，[origin] 是详情页的完整原署名。
 * 复合署名会在顶部给出片段切换条——选错人不用返回详情重进。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthorComicsScreen(
    author: String,
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
    origin: String = author,
    onKeywordSearch: (String) -> Unit = {},
    viewModel: AuthorViewModel = viewModel(),
) {
    val comics by viewModel.comics.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val endReached by viewModel.endReached.collectAsState()
    val error by viewModel.error.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val status by viewModel.status.collectAsState()
    val totalPages by viewModel.totalPages.collectAsState()
    val currentPage by viewModel.currentPage.collectAsState()
    val listState = rememberLazyGridState()
    val activeSource by SourceManager.activeSource.collectAsState()
    val supportedSorts = remember(activeSource) { SourceManager.current().supportedSorts }

    val segments = remember(origin) { AuthorNameParser.parse(origin) }
    // 当前检索片段：从详情页进来时是选中项；页内切换只改它，不新增返回栈
    var currentQuery by rememberSaveable(author, origin) { mutableStateOf(author) }
    val snackbarHostState = remember { SnackbarHostState() }
    val copier = rememberClipboardCopier(snackbarHostState)
    val copyAuthor: () -> Unit = { copier.copy("作者名", currentQuery, "已复制作者名") }

    // 保存滚动位置（每次 Activity 暂停时都保存，覆盖所有导航场景）
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        viewModel.saveScrollState(listState.firstVisibleItemIndex, currentPage)
    }
    // 恢复滚动状态（导航返回后首次 recompose 时执行）：
    // 恢复走专用通道只回填页码，绝不能复用 loadComics——否则切后台回来后的
    // 第一次排序/筛选点击会被恢复分支拦截成"只回填不加载"
    LaunchedEffect(Unit) {
        val restorePage = viewModel.consumeScrollRestorePage()
        if (restorePage == null) {
            viewModel.loadComics(currentQuery, page = 1)
        }
        if (viewModel.savedFirstVisibleIndex > 0) {
            listState.scrollToItem(viewModel.savedFirstVisibleIndex)
        }
    }

    LaunchedEffect(activeSource, supportedSorts) {
        // 空列表兜底：supportedSorts 由源声明，异常返回空时会越界崩溃
        supportedSorts.firstOrNull()?.let { def ->
            if (viewModel.sort.value !in supportedSorts) viewModel.setSort(def)
        }
    }

    // 组合期不读盘：初值 false，LaunchedEffect 中查询
    var favourited by remember { mutableStateOf(false) }
    LaunchedEffect(currentQuery) {
        favourited = AuthorFavourites.contains(currentQuery)
    }

    val switchQuery: (String) -> Unit = { query ->
        val next = query.trim()
        if (next.isNotEmpty() && next != currentQuery) {
            currentQuery = next
            viewModel.loadComics(next, page = 1)
            listState.requestScrollToItem(0)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = currentQuery,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .pointerInput(currentQuery) {
                                detectTapGestures(onLongPress = { copyAuthor() })
                            }
                            .semantics {
                                customActions = listOf(
                                    CustomAccessibilityAction("复制作者名") {
                                        copyAuthor()
                                        true
                                    },
                                )
                            },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = copyAuthor) {
                        Icon(
                            Icons.Filled.ContentCopy,
                            contentDescription = "复制作者名",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    IconButton(onClick = {
                        if (favourited) {
                            AuthorFavourites.remove(currentQuery)
                        } else {
                            AuthorFavourites.add(
                                author = currentQuery,
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
            if (segments.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(segments, key = { it.text }) { segment ->
                        PikaFilterChip(
                            selected = currentQuery == segment.text,
                            onClick = { switchQuery(segment.text) },
                            label = { AuthorSegmentLabel(segment) },
                        )
                    }
                    item(key = "__origin__") {
                        PikaFilterChip(
                            selected = currentQuery == origin,
                            onClick = { switchQuery(origin) },
                            text = "完整署名",
                        )
                    }
                }
                Text(
                    text = if (currentQuery == origin) {
                        "该署名包含多位作者，可点击上方片段分别查看"
                    } else {
                        "正在浏览署名中包含「$currentQuery」的作品 · 原署名：$origin"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(supportedSorts, key = { it.name }) { s ->
                    PikaFilterChip(
                        selected = sort == s,
                        onClick = { viewModel.setSort(s) },
                        text = s.label,
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ComicStatus.entries.toList(), key = { it.name }) { st ->
                    PikaFilterChip(
                        selected = status == st,
                        onClick = { viewModel.setStatus(st) },
                        text = st.label,
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (currentQuery == origin) {
                                "该作者暂无作品"
                            } else {
                                "没有找到署名包含「$currentQuery」的作品"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (currentQuery != origin) {
                                OutlinedButton(onClick = { switchQuery(origin) }) {
                                    Text("查看完整署名")
                                }
                            }
                            OutlinedButton(onClick = { onKeywordSearch(currentQuery) }) {
                                Text("用关键词搜索")
                            }
                        }
                    }
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
                    currentPage = currentPage,
                    totalPages = totalPages,
                    onPageChange = { p ->
                        viewModel.jumpToPage(p)
                        // 服务端换页：回顶展示新页（返回定位不受影响，恢复只在导航返回重组时执行）
                        listState.requestScrollToItem(0)
                    },
                )
            }
        }
    }
}

@Composable
private fun AuthorSegmentLabel(segment: AuthorSegment) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(segment.text)
        segment.role.label?.let { role ->
            Spacer(Modifier.width(4.dp))
            Text(
                text = role,
                style = MaterialTheme.typography.labelSmall,
                color = LocalContentColor.current.copy(alpha = 0.75f),
            )
        }
    }
}
