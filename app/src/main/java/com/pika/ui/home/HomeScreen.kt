package com.pika.ui.home

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import com.pika.data.RecentRead
import com.pika.ui.browse.ComicGridView

private val rankTypes = listOf("H24" to "日榜", "D7" to "周榜", "D30" to "月榜")

/**
 * 首页：顶部标签「关注 / 排行榜」。
 * 关注（默认）：上次浏览记录 + 关注信息流（作者/关键词/分类标签最新更新）。
 * 排行榜：日榜 / 周榜 / 月榜切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onComicClick: (String) -> Unit = {},
    onResumeReading: (String, Int) -> Unit = { _, _ -> },
    onOpenHistory: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    val recentReads by viewModel.recentReads.collectAsState()
    val followSections by viewModel.followSections.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val rankComics by viewModel.rankComics.collectAsState()
    val rankType by viewModel.rankType.collectAsState()
    val rankLoading by viewModel.rankLoading.collectAsState()
    val updateInfo by com.pika.core.update.UpdateState.updateInfo.collectAsState()
    var showUpdateDialog by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        com.pika.core.update.UpdateState.checkOnce()
        viewModel.ensureLoaded()
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && rankComics.isEmpty()) {
            viewModel.loadRank(rankType)
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
                }
            }
        },
    ) { innerPadding ->
        when (selectedTab) {
            0 -> FollowTab(
                recentReads = recentReads,
                followSections = followSections,
                refreshing = refreshing,
                onRefresh = viewModel::refresh,
                onResumeReading = onResumeReading,
                onOpenHistory = onOpenHistory,
                onComicClick = onComicClick,
                modifier = Modifier.padding(innerPadding),
            )
            else -> RankTab(
                rankComics = rankComics,
                rankType = rankType,
                loading = rankLoading,
                onTypeChange = viewModel::loadRank,
                onComicClick = onComicClick,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/** 关注信息流：上次浏览记录 + 关注的更新 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FollowTab(
    recentReads: List<RecentRead>,
    followSections: List<FollowSection>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onResumeReading: (String, Int) -> Unit,
    onOpenHistory: () -> Unit,
    onComicClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 8.dp),
        ) {
            // ── 上次浏览记录 ──────────────────────────────────────
            if (recentReads.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = "上次浏览记录",
                        action = "全部",
                        onAction = onOpenHistory,
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(recentReads, key = { it.comicId }) { recent ->
                            RecentCard(
                                recent = recent,
                                onClick = { onResumeReading(recent.comicId, recent.order) },
                            )
                        }
                    }
                }
            }

            // ── 关注的更新 ───────────────────────────────────────
            item {
                SectionHeader(title = "关注的更新", action = null, onAction = null)
            }
            if (followSections.isEmpty() && !refreshing) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "还没有关注内容，去「我的 → 关注管理」添加作者 / 关键词 / 分类标签",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(followSections, key = { "${it.type}_${it.name}" }) { section ->
                FollowSectionBlock(
                    section = section,
                    onComicClick = onComicClick,
                )
            }
        }
    }
}

/** 排行榜：日/周/月切换 + 网格 */
@Composable
private fun RankTab(
    rankComics: List<ComicSummary>,
    rankType: String,
    loading: Boolean,
    onTypeChange: (String) -> Unit,
    onComicClick: (String) -> Unit,
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
        if (rankComics.isEmpty() && loading) {
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
                listState = rememberLazyGridState(),
                onLoadMore = {},
                onComicClick = onComicClick,
            )
        }
    }
}

/** 区块标题 + 右侧操作入口 */
@Composable
private fun SectionHeader(title: String, action: String?, onAction: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (action != null && onAction != null) {
            Text(
                text = action,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(4.dp),
            )
        }
    }
}

/** 上次浏览记录卡片 */
@Composable
private fun RecentCard(recent: RecentRead, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Text(
                text = recent.title,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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

/** 关注的更新：分组子区块（名称 + 最新作品横滑条） */
@Composable
private fun FollowSectionBlock(
    section: FollowSection,
    onComicClick: (String) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Text(
            text = "${section.type} · ${section.name}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(section.comics, key = { it.id }) { comic ->
                FollowComicCard(comic = comic, onClick = { onComicClick(comic.id) })
            }
        }
    }
}

/** 关注区块内的小封面卡 */
@Composable
private fun FollowComicCard(comic: ComicSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(110.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (comic.coverUrl != null) {
                coil.compose.AsyncImage(
                    model = comic.coverUrl,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = comic.title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
        )
    }
}
