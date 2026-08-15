package com.pika.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.source.SourceManager
import com.pika.ui.browse.ComicGridView

private val rankTypes = listOf("H24" to "日榜", "D7" to "周榜", "D30" to "月榜")

/**
 * 首页：顶部标签「关注 / 排行榜」。
 * 关注（默认）：关注信息流（作者/关键词/分类标签最新更新，按时间排序的聚合网格，滚动加载）。
 * 排行榜：日榜 / 周榜 / 月榜切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onComicClick: (String) -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    val followFeed by viewModel.followFeed.collectAsState()
    val followEndReached by viewModel.followEndReached.collectAsState()
    val followLoading by viewModel.followLoading.collectAsState()
    val followEmptyHint by viewModel.followEmptyHint.collectAsState()
    val followError by viewModel.followError.collectAsState()
    val rankComics by viewModel.rankComics.collectAsState()
    val rankType by viewModel.rankType.collectAsState()
    val rankLoading by viewModel.rankLoading.collectAsState()
    val rankError by viewModel.rankError.collectAsState()
    val updateInfo by com.pika.core.update.UpdateState.updateInfo.collectAsState()
    val followRefreshTick by viewModel.refreshTick.collectAsState()
    val randomComics by viewModel.randomComics.collectAsState()
    val randomLoading by viewModel.randomLoading.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(1) }

    val followGridState = rememberLazyGridState()
    val rankGridState = rememberLazyGridState()
    val randomGridState = rememberLazyGridState()
    val scrollStateRestored by viewModel.isScrollStateRestored.collectAsState()

    // 保存当前 Tab 的滚动位置（导航离开时）
    DisposableEffect(Unit) {
        onDispose {
            viewModel.saveScrollState(selectedTab, followGridState.firstVisibleItemIndex)
        }
    }
    // 恢复滚动位置
    LaunchedEffect(scrollStateRestored) {
        val index = when (selectedTab) {
            0 -> viewModel.savedFollowIndex
            1 -> viewModel.savedRankIndex
            else -> viewModel.savedRandomIndex
        }
        if (index > 0) {
            when (selectedTab) {
                0 -> followGridState.scrollToItem(index)
                1 -> rankGridState.scrollToItem(index)
                else -> randomGridState.scrollToItem(index)
            }
            viewModel.markScrollStateRestored()
        }
    }

    LaunchedEffect(Unit) {
        com.pika.core.update.UpdateState.checkOnce()
        viewModel.ensureFollowTargets()
        viewModel.refresh()
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOnResume()
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && rankComics.isEmpty() && rankError == null) {
            viewModel.loadRank(rankType)
        } else if (selectedTab == 2) {
            viewModel.ensureRandomLoaded()
        }
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
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("关注") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("排行榜") },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("随便看看") },
                    )
                }
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> FollowTab(
                comics = followFeed,
                loading = followLoading,
                endReached = true,
                emptyHint = followEmptyHint,
                error = followError,
                refreshTick = followRefreshTick,
                onLoadMore = {},
                onRefresh = viewModel::refresh,
                onComicClick = onComicClick,
                gridState = followGridState,
                modifier = Modifier.padding(innerPadding),
            )
            1 -> RankTab(
                rankComics = rankComics,
                rankType = rankType,
                loading = rankLoading,
                error = rankError,
                onTypeChange = viewModel::loadRank,
                onComicClick = onComicClick,
                gridState = rankGridState,
                modifier = Modifier.padding(innerPadding),
            )
            else -> RandomTab(
                comics = randomComics,
                loading = randomLoading,
                onRefresh = viewModel::refreshRandom,
                onComicClick = onComicClick,
                gridState = randomGridState,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/** 关注信息流：聚合网格（按更新时间由近至远，滚动加载） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowTab(
    comics: List<com.pika.core.model.ComicSummary>,
    loading: Boolean,
    endReached: Boolean,
    emptyHint: String?,
    error: String? = null,
    refreshTick: Int,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onComicClick: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier = Modifier,
) {
    // 刷新完成后回到顶部，从最新内容开始展示
    LaunchedEffect(refreshTick) {
        if (refreshTick > 0 && comics.isNotEmpty()) {
            gridState.scrollToItem(0)
        }
    }
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        if (comics.isEmpty() && emptyHint != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (error != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRefresh) {
                            Text("重试")
                        }
                    }
                }
                ComicGridView(
                    comics = comics,
                    loading = loading,
                    endReached = endReached,
                    listState = gridState,
                    onLoadMore = onLoadMore,
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                    showTailLoading = false,
                )
            }
        }
    }
}

/** 排行榜：日/周/月切换 + 网格（失败显示错误与重试） */
@Composable
private fun RankTab(
    rankComics: List<com.pika.core.model.ComicSummary>,
    rankType: String,
    loading: Boolean,
    error: String?,
    onTypeChange: (String) -> Unit,
    onComicClick: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rankTypes.forEach { (value, label) ->
                FilterChip(
                    selected = rankType == value,
                    onClick = { onTypeChange(value) },
                    label = { Text(label) },
                )
            }
        }
        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    TextButton(onClick = { onTypeChange(rankType) }) {
                        Text("重试")
                    }
                }
            }
        } else if (rankComics.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("加载中...", style = MaterialTheme.typography.bodyMedium)
            }
        } else if (rankComics.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "排行榜暂无数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ComicGridView(
                comics = rankComics,
                loading = loading,
                endReached = true,
                listState = gridState,
                onLoadMore = {},
                onComicClick = onComicClick,
            )
        }
    }
}

/** 随便看看：随机推荐，下拉刷新换一批 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RandomTab(
    comics: List<com.pika.core.model.ComicSummary>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onComicClick: (String) -> Unit,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = loading,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        if (comics.isEmpty() && !loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "点击刷新随机推荐",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ComicGridView(
                comics = comics,
                loading = loading,
                endReached = true,
                listState = gridState,
                onLoadMore = {},
                onComicClick = onComicClick,
                showTailLoading = false,
            )
        }
    }
}