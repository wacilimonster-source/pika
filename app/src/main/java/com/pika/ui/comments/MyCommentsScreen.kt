package com.pika.ui.comments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pika.core.model.MyComicComment
import com.pika.core.source.SourceManager
import kotlinx.coroutines.launch

/** 我的评论：当前源账号的评论历史（哔咔） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyCommentsScreen(
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<MyComicComment>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var endReached by remember { mutableStateOf(false) }

    fun load(p: Int) {
        if (loading || (p > 1 && endReached)) return
        loading = true
        error = null
        scope.launch {
            try {
                val result = SourceManager.current().myComments(p)
                comments = if (p == 1) result.items else comments + result.items
                endReached = p >= result.pages
                page = p
            } catch (e: UnsupportedOperationException) {
                error = "当前源不支持我的评论"
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { load(1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的评论") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            when {
                error != null && comments.isEmpty() -> {
                    Text(
                        text = error ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                comments.isEmpty() && !loading -> {
                    Text(
                        text = "暂无评论",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(comments, key = { it.id }) { comment ->
                            MyCommentRow(comment, onClick = {
                                if (comment.comicId.isNotBlank()) onComicClick(comment.comicId)
                            })
                        }
                        if (loading) {
                            item {
                                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(Modifier.padding(12.dp))
                                }
                            }
                        }
                        if (!endReached && comments.isNotEmpty() && !loading) {
                            item {
                                Text(
                                    text = "加载更多",
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { load(page + 1) }
                                        .padding(16.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyCommentRow(comment: MyComicComment, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = comment.content,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "《${comment.comicTitle.ifBlank { "未知漫画" }}》",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "${comment.createdAt.take(10)} · 赞 ${comment.likesCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
}
