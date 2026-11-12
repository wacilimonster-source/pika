package com.pika.ui.settings

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pika.core.download.DownloadManager
import com.pika.core.source.SourceManager
import com.pika.core.update.UpdateManager
import com.pika.data.DownloadPrefs
import com.pika.data.GridSettings
import com.pika.data.ReaderPrefs
import com.pika.ui.common.PikaSegmentedControl
import com.pika.ui.download.formatBytes
import com.pika.util.CacheCleaner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** 从"我的"页进入时为 push 页面，显示返回按钮 */
    onBack: (() -> Unit)? = null,
    onOpenLog: () -> Unit = {},
    onOpenSourceManage: () -> Unit = {},
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    val hideBottomBarInReader by ReaderPrefs.current().hideBottomBarInReader
        .collectAsState(initial = true)
    val volumeKeyPaging by ReaderPrefs.current().volumeKeyPaging
        .collectAsState(initial = false)
    val gridColumns by GridSettings.columnsFlow.collectAsState()
    val scope = rememberCoroutineScope()
    var readerMode by remember { mutableStateOf(ReaderPrefs.current().readerMode) }
    var showAbout by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── 数据源 ──────────────────────────────────────────────
            SettingsGroup(header = "数据源") {
                ListItem(
                    headlineContent = { Text("数据源管理") },
                    supportingContent = { Text("账号登录等源级设置") },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeSource.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(4.dp))
                            ChevronIcon()
                        }
                    },
                    modifier = Modifier.clickable(onClick = onOpenSourceManage),
                )
            }

            // ── 浏览 ────────────────────────────────────────────────
            SettingsGroup(header = "浏览") {
                Column {
                    ListItem(
                        headlineContent = { Text("作品列表每行数量") },
                        supportingContent = {
                            Text("2 个封面更大，3 个一屏浏览更多；切换后即时生效")
                        },
                    )
                    PikaSegmentedControl(
                        items = listOf(2, 3),
                        selected = gridColumns,
                        onSelect = { GridSettings.setColumns(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        equalWidth = true,
                        label = { "$it 个" },
                    )
                }
            }

            // ── 阅读 ────────────────────────────────────────────────
            SettingsGroup(header = "阅读") {
                Column {
                    ListItem(
                        headlineContent = { Text("默认阅读模式") },
                        supportingContent = {
                            Text("滚动流适合条漫，横滑翻页适合页漫；阅读页内可随时切换")
                        },
                    )
                    PikaSegmentedControl(
                        items = listOf(0, 1),
                        selected = readerMode,
                        onSelect = {
                            readerMode = it
                            ReaderPrefs.current().readerMode = it
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        equalWidth = true,
                        label = { if (it == 0) "滚动流" else "横滑翻页" },
                    )
                }
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("阅读时隐藏底部导航栏") },
                    supportingContent = {
                        Text("看漫画时隐藏底部标签栏，画面向下延伸至屏幕底边")
                    },
                    trailingContent = {
                        Switch(
                            checked = hideBottomBarInReader,
                            onCheckedChange = {
                                scope.launch { ReaderPrefs.current().setHideBottomBarInReader(it) }
                            },
                        )
                    },
                )
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("音量键翻页") },
                    supportingContent = {
                        Text("阅读页内音量上/下键翻上一页/下一页（不影响媒体音量）")
                    },
                    trailingContent = {
                        Switch(
                            checked = volumeKeyPaging,
                            onCheckedChange = {
                                scope.launch { ReaderPrefs.current().setVolumeKeyPaging(it) }
                            },
                        )
                    },
                )
            }

            // ── 下载 ────────────────────────────────────────────────
            SettingsGroup(header = "下载") {
                val notifyEnabled by DownloadPrefs.notifyEnabledFlow
                    .collectAsState(initial = DownloadPrefs.notifyEnabled)
                val wifiOnly by DownloadPrefs.wifiOnlyFlow
                    .collectAsState(initial = DownloadPrefs.wifiOnly)
                val context = LocalContext.current
                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    // S4：开关状态必须跟随真实权限授予结果。此前回调为空体——用户在系统
                    // 弹窗点「拒绝」后 Switch 仍显示开启，实际一条通知都不会发，
                    // 且权限已拒绝后系统也不再弹申请，用户无从自救。
                    scope.launch { DownloadPrefs.setNotifyEnabled(granted) }
                    if (!granted) {
                        Toast.makeText(
                            context,
                            "未授予通知权限：下载完成将不会有系统通知，可在系统设置中开启",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                ListItem(
                    headlineContent = { Text("下载完成通知") },
                    supportingContent = { Text("整本下载完成后发送系统通知") },
                    trailingContent = {
                        Switch(
                            checked = notifyEnabled,
                            onCheckedChange = { enabled ->
                                if (enabled && Build.VERSION.SDK_INT >= 33) {
                                    // 授予结果由上方回调写入：不再无条件置 true
                                    notifPermissionLauncher.launch(
                                        Manifest.permission.POST_NOTIFICATIONS,
                                    )
                                } else {
                                    scope.launch { DownloadPrefs.setNotifyEnabled(enabled) }
                                }
                            },
                        )
                    },
                )
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("仅 Wi-Fi 下载") },
                    supportingContent = {
                        Text("流量网络下任务自动排队，连接 Wi-Fi 后继续下载")
                    },
                    trailingContent = {
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                scope.launch { DownloadPrefs.setWifiOnly(it) }
                                // 开关变化即时重调度：关闭「仅 Wi-Fi」后流量网络下的排队任务立刻补位启动（S14-3）
                                DownloadManager.notifyWifiOnlyChanged()
                            },
                        )
                    },
                )
            }

            // ── 通用 ────────────────────────────────────────────────
            SettingsGroup(header = "通用") {
                UpdateSection()
                SettingsRowDivider()
                CacheClearRow()
                ListItem(
                    headlineContent = { Text("调试日志") },
                    supportingContent = { Text("查看应用运行日志") },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { ChevronIcon() },
                    modifier = Modifier.clickable(onClick = onOpenLog),
                )
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("关于 PiKA") },
                    supportingContent = { Text("版本 ${UpdateManager.currentVersionName}") },
                    leadingContent = {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = { ChevronIcon() },
                    modifier = Modifier.clickable { showAbout = true },
                )
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

/** 通用组 · 检查更新行（弹窗流程与原实现一致） */
@Composable
private fun UpdateSection() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // 检查结果留在本地（检查很快，随页面销毁无所谓）；下载态以 UpdateManager 为准
    // （app 级作用域）：下载中关对话框/离开设置页都不再静默取消，回来进度仍可见、可重试
    var checkState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var lastFound by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    val dlState by UpdateManager.downloadUi.collectAsState()
    var dialogOpen by remember { mutableStateOf(false) }
    val state: UpdateUiState = when (val d = dlState) {
        is UpdateManager.DownloadUi.Downloading -> UpdateUiState.Downloading
        is UpdateManager.DownloadUi.Done -> UpdateUiState.Downloaded
        is UpdateManager.DownloadUi.Failed -> UpdateUiState.DownloadFailed(d.message)
        UpdateManager.DownloadUi.Idle -> checkState
    }
    val progress = (dlState as? UpdateManager.DownloadUi.Downloading)?.progress ?: 0f

    ListItem(
        headlineContent = { Text("检查更新") },
        supportingContent = { Text("发现新版本时提示下载安装") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == UpdateUiState.Checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = "v${UpdateManager.currentVersionName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
            modifier = Modifier
                .clickable {
                    if (checkState == UpdateUiState.Checking) return@clickable
                    scope.launch {
                        checkState = UpdateUiState.Checking
                        dialogOpen = true
                        val result = UpdateManager.checkResult()
                        // 上一轮已完成的下载状态让位给新一轮检查结果（下载中则内部忽略，后台继续）
                        UpdateManager.resetDownload()
                        checkState = when (result) {
                            is UpdateManager.CheckResult.Available -> {
                                lastFound = result.info
                                UpdateUiState.Found(result.info)
                            }
                            UpdateManager.CheckResult.UpToDate -> UpdateUiState.UpToDate
                            is UpdateManager.CheckResult.Failed -> UpdateUiState.Error
                        }
                    }
                },
    )

    if (dialogOpen) {
        AlertDialog(
            // 下载已移到 UpdateManager 的 app 级作用域：随时可关，
            // 下载在后台继续，重开对话框进度仍可见（与"进度仍可见"的一致行为）
            onDismissRequest = { dialogOpen = false },
            title = {
                Text(
                    when (state) {
                        is UpdateUiState.Found -> "发现新版本 ${(state as UpdateUiState.Found).info.version}"
                        UpdateUiState.Checking -> "检查更新"
                        UpdateUiState.Downloading -> "下载更新"
                        UpdateUiState.Downloaded -> "下载完成"
                        is UpdateUiState.DownloadFailed -> "下载失败"
                        UpdateUiState.UpToDate -> "已是最新版本"
                        UpdateUiState.Error -> "检查失败"
                        UpdateUiState.Idle -> "更新"
                    }
                )
            },
            text = {
                when (state) {
                    UpdateUiState.Checking -> Text("正在检查…")
                    UpdateUiState.UpToDate -> Text(
                        "当前版本 ${UpdateManager.currentVersionName} 已是最新。"
                    )
                    is UpdateUiState.Found -> {
                        val info = (state as UpdateUiState.Found).info
                        Column {
                            Text("新版本：${info.version}")
                            if (info.notes.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    info.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    UpdateUiState.Downloading -> Column {
                        if (progress >= 0f) {
                            LinearProgressIndicator(
                                progress = { progress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("${(progress * 100).toInt()}%")
                        } else {
                            Text("下载中…（总大小未知）")
                        }
                    }
                    UpdateUiState.Downloaded -> Text("APK 已下载，点击安装完成更新。")
                    is UpdateUiState.DownloadFailed -> Text("${state.message}，可重试。")
                    UpdateUiState.Error -> Text("网络异常或服务器未就绪，请稍后重试。")
                    UpdateUiState.Idle -> Text("")
                }
            },
            confirmButton = {
                when (state) {
                    is UpdateUiState.Found -> {
                        val info = (state as UpdateUiState.Found).info
                        Button(onClick = {
                            // 下载跑在 UpdateManager 的 app 级作用域，页面销毁不影响
                            UpdateManager.startDownload(context, info)
                        }) { Text("下载") }
                    }
                    is UpdateUiState.DownloadFailed -> {
                        Button(onClick = {
                            lastFound?.let { UpdateManager.startDownload(context, it) }
                        }) { Text("重试") }
                    }
                    UpdateUiState.Downloaded -> {
                        val apk = (dlState as? UpdateManager.DownloadUi.Done)?.apk
                        if (apk != null) {
                            Button(onClick = {
                                UpdateManager.install(context, apk)
                            }) { Text("安装") }
                        } else {
                            TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
                        }
                    }
                    UpdateUiState.Downloading -> TextButton(onClick = {}, enabled = false) { Text("下载中…") }
                    else -> {
                        TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
                    }
                }
            },
            dismissButton = {
                when {
                    checkState is UpdateUiState.Found || checkState == UpdateUiState.Checking -> {
                        TextButton(onClick = { dialogOpen = false }) { Text("取消") }
                    }
                    state == UpdateUiState.Downloading -> {
                        // 下载转入后台继续：对话框可关，进度在 UpdateManager 里不丢
                        TextButton(onClick = { dialogOpen = false }) { Text("后台运行") }
                    }
                    state == UpdateUiState.Downloaded -> {
                        TextButton(onClick = { dialogOpen = false }) { Text("稍后安装") }
                    }
                    // 其余状态 confirmButton 已是「关闭」：不再重复渲染第二个「关闭」
                    else -> null
                }
            },
        )
    }
}

/** 关于对话框：版本、简介与内容来源声明 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于 PiKA") },
        text = {
            Column {
                Text(
                    "PiKA · 聚合漫画阅读器",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "当前版本 ${UpdateManager.currentVersionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "基于哔咔漫画数据源，支持搜索、分类浏览、关注流、下载与本地阅读进度。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "本应用仅提供聚合与阅读功能，所有内容均来自第三方站点，版权归原作者及对应站点所有。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 更新弹窗状态 */
private sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Found(val info: UpdateManager.UpdateInfo) : UpdateUiState
    data object Downloading : UpdateUiState
    data object Downloaded : UpdateUiState
    /** 下载失败（区别于检查失败）：保留服务端原因文案，可重试 */
    data class DownloadFailed(val message: String) : UpdateUiState
    data object Error : UpdateUiState
}

/** 通用组 · 清理缓存行（图片磁盘缓存 + 内存缓存；已下载内容不受影响） */
@Composable
private fun CacheClearRow() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizeText by remember { mutableStateOf("统计中…") }
    var clearing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sizeText = formatBytes(
            CacheCleaner.imageCacheSize(context),
        )
    }

    ListItem(
        headlineContent = { Text("清理缓存") },
        supportingContent = { Text("图片缓存 $sizeText（已下载的漫画不受影响）") },
        trailingContent = {
            if (clearing) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        modifier = Modifier.clickable(enabled = !clearing) {
            clearing = true
            scope.launch {
                val cleared = CacheCleaner.clearImageCache(context)
                sizeText = formatBytes(
                    CacheCleaner.imageCacheSize(context),
                )
                clearing = false
                Toast.makeText(
                    context,
                    "已清理 ${com.pika.ui.download.formatBytes(cleared)}",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )
}
