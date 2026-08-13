package com.pika.ui.download

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pika.core.download.DlStatus
import com.pika.core.download.DownloadManager
import com.pika.core.download.TaskRuntime
import java.util.Locale

/**
 * 下载管理页：总存储占用 / 实时速度 / 每任务进度 / 失败重试 / 删除。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    onBack: () -> Unit,
    onComicClick: (comicId: String, order: Int) -> Unit = { _, _ -> },
) {
    val tasks by DownloadManager.tasks.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的下载") },
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
            // 总览
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "已占用 ${formatBytes(DownloadManager.totalBytes)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${tasks.count { it.isFinished }}/${tasks.size} 个任务完成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val speed = DownloadManager.totalSpeed
                    if (speed > 0) {
                        Text(
                            text = "↓ ${formatBytes(speed)}/s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "暂无下载任务\n在漫画详情页点击下载按钮即可开始",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(tasks, key = { it.key }) { runtime ->
                        DownloadTaskRow(
                            runtime = runtime,
                            onClick = {
                                if (runtime.isFinished) {
                                    onComicClick(runtime.task.comicId, runtime.task.order)
                                }
                            },
                            onRetry = { DownloadManager.retry(runtime.key) },
                            onDelete = { DownloadManager.remove(runtime.key) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadTaskRow(
    runtime: TaskRuntime,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    val task = runtime.task
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = task.coverUrl,
                contentDescription = task.comicTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.comicTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = task.epTitle.ifBlank { "第 ${task.order} 话" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                when (runtime.status) {
                    DlStatus.DOWNLOADING, DlStatus.PENDING -> {
                        LinearProgressIndicator(
                            progress = { runtime.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                append("${runtime.progress}% · ${runtime.downloadedPages}/${task.pageCount} 页")
                                append(" · ${formatBytes(runtime.totalBytes)}")
                                if (runtime.bytesPerSecond > 0) {
                                    append(" · ${formatBytes(runtime.bytesPerSecond)}/s")
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    DlStatus.COMPLETED -> {
                        Text(
                            text = "已完成 · ${task.pageCount} 页 · ${formatBytes(runtime.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    DlStatus.FAILED -> {
                        Text(
                            text = "失败：${runtime.error.ifBlank { "网络错误" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    else -> {}
                }
            }
            if (runtime.status == DlStatus.FAILED) {
                IconButton(onClick = onRetry) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "重试",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 人类可读体积 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
