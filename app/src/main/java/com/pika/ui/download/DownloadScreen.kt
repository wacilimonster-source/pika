package com.pika.ui.download

import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pika.core.download.DlStatus
import com.pika.core.download.DownloadManager
import com.pika.core.download.DownloadExporter
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
    // 显式订阅派生状态：总占用 / 总速度独立刷新，不依赖 tasks 的隐式读取
    val totalBytes by DownloadManager.totalBytesFlow.collectAsState()
    val totalSpeed by DownloadManager.totalSpeedFlow.collectAsState()
    // 整本删除确认（此前点击即删，无法撤销）：comicId to 标题
    var pendingDeleteAll by remember { mutableStateOf<Pair<String, String>?>(null) }
    // 仅 Wi-Fi 下载：流量网络 + 有排队任务时给出可见提示
    val wifiOnly by com.pika.data.DownloadPrefs.wifiOnlyFlow
        .collectAsState(initial = com.pika.data.DownloadPrefs.wifiOnly)
    val context = androidx.compose.ui.platform.LocalContext.current

    // 导出（P3 #3）：点导出 → SAF CreateDocument 选位置 → 下载器流式写 zip。
    // pendingExport = 待导出 (comicId, 标题)；partialExportConfirm = 部分完成时先确认
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }
    var partialExportConfirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exportState by DownloadExporter.state.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) {
            DownloadExporter.start(context, request.first, uri)
        }
    }
    // 导出完成/失败 → Snackbar（Running 状态由顶部进度卡实时展示）
    LaunchedEffect(exportState) {
        when (val s = exportState) {
            is DownloadExporter.ExportState.Done -> {
                snackbarHostState.showSnackbar("导出完成：${s.fileName}（${s.chapterCount} 章）")
            }
            is DownloadExporter.ExportState.Error -> {
                snackbarHostState.showSnackbar(s.message)
            }
            else -> {}
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("我的下载") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                            text = "已占用 ${formatBytes(totalBytes)}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "${tasks.count { it.isFinished }}/${tasks.size} 个任务完成",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val speed = totalSpeed
                    if (speed > 0) {
                        Text(
                            text = "↓ ${formatBytes(speed)}/s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            // 仅 Wi-Fi 下载排队提示：让"任务为什么不开始"有明确解释
            if (wifiOnly &&
                tasks.any { it.status == DlStatus.PENDING } &&
                isNetworkMetered(context)
            ) {
                Text(
                    text = "当前为流量网络，已开启「仅 Wi-Fi 下载」，任务将在连接 Wi-Fi 后自动继续",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            // 导出进度卡：流式写 zip 期间可见、可取消（完成/失败走 Snackbar）
            (exportState as? DownloadExporter.ExportState.Running)?.let { running ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (running.chapterIndex == 0) {
                                    "正在导出《${running.comicTitle}》…"
                                } else {
                                    "正在导出《${running.comicTitle}》第 ${running.chapterIndex}/${running.chapterCount} 话 · ${running.chapterTitle}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { running.chapterIndex / running.chapterCount.toFloat() },
                                modifier = Modifier.fillMaxWidth().height(3.dp),
                            )
                        }
                        TextButton(onClick = { DownloadExporter.cancel() }) {
                            Text("取消")
                        }
                    }
                }
            }

            // 展开态上提到分支外：此前 remember 位于 else 分支内，
            // tasks 在"空 ↔ 非空"之间切换时会被重置，展开状态丢失
            var expandedComic by remember { mutableStateOf<Set<String>>(emptySet()) }

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
                // 按漫画分组：漫画头卡片 + 展开的章节行
                val grouped = tasks
                    .groupBy { it.task.comicId }
                    .entries
                    .sortedByDescending { it.value.maxOfOrNull { t -> t.task.createdAt } ?: 0L }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(grouped, key = { it.key }) { (comicId, comicTasks) ->
                        val expanded = comicId in expandedComic
                        // A5 增删动画：删除任务/整本时卡片平滑收起
                        Box(Modifier.animateItem()) {
                            ComicGroupCard(
                            comicId = comicId,
                            tasks = comicTasks,
                            expanded = expanded,
                            onToggle = {
                                expandedComic = if (expanded) {
                                    expandedComic - comicId
                                } else {
                                    expandedComic + comicId
                                }
                            },
                            onChapterClick = { order ->
                                if (comicTasks.any { t -> t.isFinished && t.task.order == order }) {
                                    onComicClick(
                                        com.pika.core.source.ComicRef.ofName(
                                            comicTasks.firstOrNull()?.task?.source, comicId,
                                        ),
                                        order,
                                    )
                                }
                            },
                            onRetry = { DownloadManager.retry(it) },
                            onDelete = { DownloadManager.remove(it) },
                            onDeleteAll = {
                                pendingDeleteAll = comicId to (comicTasks.firstOrNull()?.task?.comicTitle ?: comicId)
                            },
                            onExport = { group ->
                                // 部分完成（还有排队/下载中章）时如实告知收录范围，用户确认后才发起
                                val done = group.count { it.isFinished }
                                val title = group.firstOrNull()?.task?.comicTitle ?: comicId
                                if (done < group.size) {
                                    partialExportConfirm = comicId to title
                                } else {
                                    pendingExport = comicId to title
                                    exportLauncher.launch(suggestedZipName(title))
                                }
                            },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDeleteAll?.let { (comicId, title) ->
        AlertDialog(
            onDismissRequest = { pendingDeleteAll = null },
            title = { Text("删除整本下载") },
            text = { Text("删除《$title》的全部下载任务与已下载文件？删除后离线不可读。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteAll = null
                    // 确认时重新取任务快照：对话框打开期间列表可能已变化
                    DownloadManager.comicTasks(comicId).forEach { DownloadManager.remove(it.key) }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAll = null }) { Text("取消") }
            },
        )
    }

    partialExportConfirm?.let { (comicId, title) ->
        // 部分完成导出确认：收录范围如实说清，避免"为什么少了几话"
        val snapshot = DownloadManager.comicTasks(comicId)
        val done = snapshot.count { it.isFinished }
        AlertDialog(
            onDismissRequest = { partialExportConfirm = null },
            title = { Text("导出《$title》") },
            text = { Text("还有 ${snapshot.size - done} 章未下载完成这次不会包含在 zip 中，\n先导出已完成的 $done 章？") },
            confirmButton = {
                TextButton(onClick = {
                    partialExportConfirm = null
                    pendingExport = comicId to title
                    exportLauncher.launch(suggestedZipName(title))
                }) { Text("导出已完成") }
            },
            dismissButton = {
                TextButton(onClick = { partialExportConfirm = null }) { Text("取消") }
            },
        )
    }
}

/** 导出文件推荐名（SAF CreateDocument 的初始输入框内容；用户可自行改名） */
private fun suggestedZipName(comicTitle: String): String =
    comicTitle
        .replace(Regex("[\\\\/:*?\"<>|\n\r]"), "_")
        .trim()
        .take(50)
        .let { if (it.isBlank()) "PiKA导出" else it } + ".zip"

/** 当前网络是否按流量计费 */
private fun isNetworkMetered(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
        as? android.net.ConnectivityManager ?: return true
    return cm.isActiveNetworkMetered
}

@Composable
private fun ComicGroupCard(
    comicId: String,
    tasks: List<TaskRuntime>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChapterClick: (Int) -> Unit,
    onRetry: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onExport: (List<TaskRuntime>) -> Unit,
) {
    val first = tasks.first()
    val task = first.task
    val done = tasks.count { it.isFinished }
    val downloading = tasks.count { it.status == DlStatus.DOWNLOADING || it.status == DlStatus.PENDING }
    val bytes = tasks.sumOf { it.totalBytes }
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = task.coverUrl,
                contentDescription = task.comicTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
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
                Spacer(Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("$done/${tasks.size} 章完成")
                        if (downloading > 0) append(" · $downloading 章下载中")
                        append(" · ${formatBytes(bytes)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (tasks.isEmpty()) 0f else done.toFloat() / tasks.size },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
            if (done > 0) {
                IconButton(onClick = { onExport(tasks) }) {
                    Icon(
                        Icons.Filled.SaveAlt,
                        contentDescription = "导出为 zip",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDeleteAll) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除整本",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            tasks.sortedBy { it.task.order }.forEach { runtime ->
                DownloadTaskRow(
                    runtime = runtime,
                    onClick = { onChapterClick(runtime.task.order) },
                    onRetry = { onRetry(runtime.key) },
                    onDelete = { onDelete(runtime.key) },
                )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = task.epTitle.ifBlank { "第 ${task.order} 话" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            when (runtime.status) {
                DlStatus.DOWNLOADING, DlStatus.PENDING -> {
                    LinearProgressIndicator(
                        progress = { runtime.progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
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
    HorizontalDivider(Modifier.padding(start = 16.dp))
}

/** 人类可读体积 */
internal fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
