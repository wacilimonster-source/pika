package com.pika.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pika.core.model.ComicChapter
import com.pika.core.model.ComicDetail

/** 漫画详情：封面 + 信息 + 简介 + 章节列表（含下载入口） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComicDetailScreen(
    comicId: String,
    onBack: () -> Unit,
    onOpenReader: (String, Int) -> Unit,
    onOpenAuthor: (String) -> Unit = {},
    viewModel: ComicDetailViewModel = viewModel(),
) {
    val comic by viewModel.comic.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val favourited by viewModel.favourited.collectAsState()
    var descExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(comicId) {
        viewModel.load(comicId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(comic?.title ?: "详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 收藏（当前源支持时可用）
                    if (viewModel.canFavourite()) {
                        IconButton(onClick = { viewModel.favourite() }) {
                            Icon(
                                imageVector = if (favourited) {
                                    Icons.Filled.Favorite
                                } else {
                                    Icons.Outlined.FavoriteBorder
                                },
                                contentDescription = "收藏",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val c = comic
            if (c != null) {
                item {
                    ComicHeader(comic = c, onOpenAuthor = onOpenAuthor)
                }
                item {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { chapters.firstOrNull()?.let { onOpenReader(comicId, it.order) } },
                            enabled = chapters.isNotEmpty(),
                        )
                        { Text("开始阅读") }
                        OutlinedButton(
                            onClick = { onOpenReader(comicId, 1) },
                        )
                        { Text("从第 1 话") }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "简介",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Text(
                        text = if (c.description.isBlank()) "暂无简介" else c.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (descExpanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    if (c.description.length > 40) {
                        Text(
                            text = if (descExpanded) "收起" else "展开",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { descExpanded = !descExpanded },
                        )
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "标签",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyRow(
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(c.tags.ifEmpty { c.categories }) { tag ->
                            SuggestionChip(onClick = {}, label = { Text(tag) })
                        }
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(
                        text = "章节（${c.epsCount}）",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                items(chapters, key = { it.id }) { chapter ->
                    ChapterRow(
                        chapter = chapter,
                        onClick = { onOpenReader(comicId, chapter.order) },
                        onDownload = { viewModel.downloadChapter(comicId, comic, chapter) },
                        downloaded = viewModel.isDownloaded(comicId, chapter),
                    )
                }
                if (loading && chapters.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                "加载章节中…",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                    }
                }
            } else {
                item {
                    Box(Modifier.fillMaxSize().padding(top = 160.dp), contentAlignment = Alignment.TopCenter) {
                        Text("加载中…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicHeader(comic: ComicDetail, onOpenAuthor: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(110.dp).aspectRatio(3f / 4f)) {
            comic.coverUrl?.let {
                AsyncImage(
                    model = it,
                    contentDescription = comic.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = comic.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            if (comic.author.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onOpenAuthor(comic.author) },
                ) {
                    Text(
                        text = comic.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "  查看作品 ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${if (comic.finished) "已完结" else "连载中"} · ${comic.pagesCount}P · ${comic.epsCount} 话",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "阅读 ${comic.totalViews} · 赞 ${comic.totalLikes} · 评 ${comic.commentsCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = comic.categories.joinToString(" / "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ComicChapter,
    onClick: () -> Unit,
    onDownload: () -> Unit,
    downloaded: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = chapter.title.ifBlank { "第 ${chapter.order} 话" },
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (downloaded) {
            Text(
                text = "已下载",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        IconButton(onClick = onDownload) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = if (downloaded) "重新下载" else "下载",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}