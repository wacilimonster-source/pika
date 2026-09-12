package com.pika.ui.settings

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import com.pika.core.source.SourceManager
import com.pika.core.source.SourceType
import com.pika.core.update.UpdateManager
import com.pika.data.ReaderPrefs
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** 从"我的"页进入时为 push 页面，显示返回按钮 */
    onBack: (() -> Unit)? = null,
    onOpenLog: () -> Unit = {},
    onOpenSourceManage: () -> Unit = {},
    onOpenLogin: () -> Unit = {},
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    // 登录态本身非响应式：401/登出会自增 tick，借此重查各源登录状态
    val unauthorizedTick by SourceManager.unauthorizedTick.collectAsState()
    val hideBottomBarInReader by ReaderPrefs.current().hideBottomBarInReader
        .collectAsState(initial = true)
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── 数据源 ──────────────────────────────────────────────
            SettingsGroup(
                header = "数据源",
                supporting = "切换后首页 / 搜索 / 详情将展示该源的内容",
            ) {
                SourceType.entries.forEachIndexed { index, type ->
                    if (index > 0) SettingsRowDivider()
                    val loggedIn = remember(type, unauthorizedTick) {
                        SourceManager.sourceOf(type).isLoggedIn
                    }
                    SourceRow(
                        type = type,
                        selected = type == activeSource,
                        loggedIn = loggedIn,
                        onSelect = { scope.launch { SourceManager.switch(type) } },
                        onLogin = if (loggedIn) null else {
                            {
                                // 切源与导航必须串行：原实现先 launch 再同步导航，
                                // 登录页可能以旧源组合，把新源账号提交给旧源
                                scope.launch {
                                    if (type != activeSource) SourceManager.switch(type)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                        onOpenLogin()
                                    }
                                }
                            }
                        },
                    )
                }
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("数据源管理") },
                    supportingContent = { Text("账号登录 · API 域名等高级设置") },
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

            // ── 阅读 ────────────────────────────────────────────────
            SettingsGroup(header = "阅读") {
                Column {
                    ListItem(
                        headlineContent = { Text("默认阅读模式") },
                        supportingContent = {
                            Text("滚动流适合条漫，横滑翻页适合页漫；阅读页内可随时切换")
                        },
                    )
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    ) {
                        SegmentedButton(
                            selected = readerMode == 0,
                            onClick = {
                                readerMode = 0
                                ReaderPrefs.current().readerMode = 0
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("滚动流") }
                        SegmentedButton(
                            selected = readerMode == 1,
                            onClick = {
                                readerMode = 1
                                ReaderPrefs.current().readerMode = 1
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("横滑翻页") }
                    }
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
            }

            // ── 通用 ────────────────────────────────────────────────
            SettingsGroup(header = "通用") {
                UpdateSection()
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("调试日志") },
                    supportingContent = { Text("查看应用运行日志") },
                    trailingContent = { ChevronIcon() },
                    modifier = Modifier.clickable(onClick = onOpenLog),
                )
                SettingsRowDivider()
                ListItem(
                    headlineContent = { Text("关于 PiKA") },
                    supportingContent = { Text("版本 ${UpdateManager.currentVersionName}") },
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

/** 数据源单选行；未登录时行尾附"去登录"，点击会切到该源并进入登录页 */
@Composable
private fun SourceRow(
    type: SourceType,
    selected: Boolean,
    loggedIn: Boolean,
    onSelect: () -> Unit,
    onLogin: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(type.displayName) },
        supportingContent = { Text(if (loggedIn) "已登录" else "未登录") },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onLogin != null) {
                    TextButton(onClick = onLogin) { Text("去登录") }
                }
                RadioButton(selected = selected, onClick = onSelect)
            }
        },
        modifier = Modifier.clickable(onClick = onSelect),
    )
}

/** 通用组 · 检查更新行（弹窗流程与原实现一致） */
@Composable
private fun UpdateSection() {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var progress by remember { mutableStateOf(0f) }
    var dialogOpen by remember { mutableStateOf(false) }

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
                if (state == UpdateUiState.Checking) return@clickable
                scope.launch {
                    state = UpdateUiState.Checking
                    dialogOpen = true
                    val result = UpdateManager.checkResult()
                    state = when (result) {
                        is UpdateManager.CheckResult.Available -> UpdateUiState.Found(result.info)
                        UpdateManager.CheckResult.UpToDate -> UpdateUiState.UpToDate
                        is UpdateManager.CheckResult.Failed -> UpdateUiState.Error
                    }
                }
            },
    )

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = {
                Text(
                    when (state) {
                        is UpdateUiState.Found -> "发现新版本 ${(state as UpdateUiState.Found).info.version}"
                        UpdateUiState.Checking -> "检查更新"
                        UpdateUiState.Downloading -> "下载更新"
                        UpdateUiState.Downloaded -> "下载完成"
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
                    UpdateUiState.Error -> Text("网络异常或服务器未就绪，请稍后重试。")
                    UpdateUiState.Idle -> Text("")
                }
            },
            confirmButton = {
                when (state) {
                    is UpdateUiState.Found -> {
                        val info = (state as UpdateUiState.Found).info
                        androidx.compose.material3.Button(onClick = {
                            state = UpdateUiState.Downloading
                            scope.launch {
                                // 用 runCatchingCancellable：对话框关闭导致的取消不能被当成"下载失败"
                                com.pika.core.runCatchingCancellable {
                                    // 下载 + SHA-256 校验：update.json 提供 sha256 时强校验
                                    UpdateManager.downloadAndVerify(context, info) { p, _, _ -> progress = p }
                                        .also { apk ->
                                            state = UpdateUiState.Downloaded
                                            UpdateManager.install(context, apk)
                                        }
                                }.onFailure {
                                    state = UpdateUiState.Error
                                }
                            }
                        }) { Text("下载") }
                    }
                    else -> {
                        TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
                    }
                }
            },
            dismissButton = {
                if (state is UpdateUiState.Found || state == UpdateUiState.Checking) {
                    TextButton(onClick = { dialogOpen = false }) { Text("取消") }
                } else {
                    TextButton(onClick = { dialogOpen = false }) { Text("关闭") }
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
                    "聚合哔咔漫画与禁漫天堂双数据源，支持搜索、分类浏览、关注流、下载与本地阅读进度。",
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
    data object Error : UpdateUiState
}
