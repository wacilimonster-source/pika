package com.pika.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pika.core.source.SourceManager
import com.pika.core.source.SourceType
import com.pika.core.update.UpdateManager
import com.pika.data.SourcePrefs
import com.pika.network.JmClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /** 从"我的"页进入时为 push 页面，显示返回按钮 */
    onBack: (() -> Unit)? = null,
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    val scope = rememberCoroutineScope()
    var jmBase by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        jmBase = SourcePrefs.current().jmBaseUrl ?: JmClient.DEFAULT_BASE
    }

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
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "数据源（低频切换，在此后台设置）",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SourceType.entries.forEach { type ->
                ListItem(
                    headlineContent = { Text(type.displayName) },
                    supportingContent = {
                        Text(
                            if (SourceManager.sourceOf(type).isLoggedIn) "已登录"
                            else "未登录"
                        )
                    },
                    trailingContent = {
                        RadioButton(
                            selected = type == activeSource,
                            onClick = { scope.launch { SourceManager.switch(type) } },
                        )
                    },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            HorizontalDivider()

            if (activeSource == SourceType.JMCOMIC) {
                Text(
                    text = "禁漫 API 域名（镜像制，登录失败或请求 404 时更换）",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    OutlinedTextField(
                        value = jmBase,
                        onValueChange = { jmBase = it },
                        singleLine = true,
                        label = { Text("域名") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val v = jmBase.trim().trimEnd('/')
                            if (v.isNotEmpty()) {
                                runBlocking { SourcePrefs.current().setJmBaseUrl(v) }
                                saved = true
                            }
                        },
                    ) { Text("保存") }
                }
                if (saved) {
                    Text(
                        text = "已保存",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                HorizontalDivider()
            }

            Text(
                text = "切换数据源后，首页 / 搜索 / 详情将展示该源的内容",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider()
            UpdateSection()
        }
    }
}

/** 关于 / 更新区块 */
@Composable
private fun UpdateSection() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var state by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var progress by remember { mutableStateOf(0f) }
    var dialogOpen by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text("检查更新") },
        supportingContent = {
            Text("当前版本 ${UpdateManager.currentVersionName}")
        },
        trailingContent = {
            if (state == UpdateUiState.Checking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        },
        modifier = Modifier
            .clickable {
                if (state == UpdateUiState.Checking) return@clickable
                scope.launch {
                    state = UpdateUiState.Checking
                    dialogOpen = true
                    val info = UpdateManager.check()
                    state = if (info == null) UpdateUiState.UpToDate else UpdateUiState.Found(info)
                }
            }
            .padding(horizontal = 8.dp),
    )
    ListItem(
        headlineContent = { Text("关于") },
        supportingContent = { Text("PiKA · 聚合漫画阅读器") },
        modifier = Modifier.padding(horizontal = 8.dp),
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
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${(progress * 100).toInt()}%")
                    }
                    UpdateUiState.Downloaded -> Text("APK 已下载，点击安装完成更新。")
                    UpdateUiState.Error -> Text("网络异常或服务器未就绪，请稍后重试。")
                    UpdateUiState.Idle -> Text("")
                }
            },
            confirmButton = {
                when (state) {
                    is UpdateUiState.Found -> {
                        val url = (state as UpdateUiState.Found).info.apkUrl
                        Button(onClick = {
                            state = UpdateUiState.Downloading
                            scope.launch {
                                runCatching {
                                    UpdateManager.download(context, url) { p -> progress = p }
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
                    UpdateUiState.Downloaded -> {
                        Button(onClick = { dialogOpen = false }) { Text("关闭") }
                    }
                    UpdateUiState.Error -> {
                        Button(onClick = { dialogOpen = false }) { Text("关闭") }
                    }
                    else -> {
                        Button(onClick = { dialogOpen = false }) { Text("关闭") }
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