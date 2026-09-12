package com.pika.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pika.core.source.SourceManager
import com.pika.core.source.SourceType
import com.pika.data.SecureAccountStore
import com.pika.data.SourcePrefs
import com.pika.network.JmClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 数据源管理二级页：各源账号状态 + 高级/故障排除（禁漫 API 域名）。
 * 登出、签到等账号操作仍在"我的"页，此处只负责登录入口与源级配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val activeSource by SourceManager.activeSource.collectAsState()
    // 登录态本身非响应式：401/登出会自增 tick，借此重查各源登录状态
    val unauthorizedTick by SourceManager.unauthorizedTick.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据源管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
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
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsGroup(header = "账号") {
                SourceType.entries.forEachIndexed { index, type ->
                    if (index > 0) SettingsRowDivider()
                    val loggedIn = remember(type, unauthorizedTick) {
                        SourceManager.sourceOf(type).isLoggedIn
                    }
                    val savedEmail = remember(type, unauthorizedTick) {
                        SecureAccountStore.savedEmail(type)
                    }
                    AccountRow(
                        type = type,
                        isCurrent = type == activeSource,
                        loggedIn = loggedIn,
                        savedEmail = savedEmail,
                        onLogin = {
                            // 串行化：先切源再导航，避免登录页以旧源组合（把账号提交给旧源）
                            scope.launch {
                                if (type != activeSource) SourceManager.switch(type)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                    onOpenLogin()
                                }
                            }
                        },
                    )
                }
            }

            SettingsGroup(
                header = "高级 · 故障排除",
                supporting = "禁漫 API 为镜像域名制，登录失败或请求 404 时更换",
            ) {
                JmDomainEditor()
            }
        }
    }
}

/** 账号状态行：未登录显示"去登录"，当前源已登录提供"重新登录"（登录页自动填充已存凭据） */
@Composable
private fun AccountRow(
    type: SourceType,
    isCurrent: Boolean,
    loggedIn: Boolean,
    savedEmail: String?,
    onLogin: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(type.displayName) },
        supportingContent = {
            buildList {
                add(if (loggedIn) "已登录" else "未登录")
                if (isCurrent) add("当前数据源")
                if (!loggedIn && savedEmail != null) add("已保存账号 $savedEmail，可一键登录")
            }.joinToString(" · ").let { Text(it) }
        },
        trailingContent = {
            when {
                !loggedIn -> TextButton(onClick = onLogin) { Text("去登录") }
                // 仅当前源提供重新登录：登录页只登录活动源，避免暗改活动源
                isCurrent -> TextButton(onClick = onLogin) { Text("重新登录") }
            }
        },
    )
}

/** 禁漫 API 域名编辑：保存走后台协程落盘，不在主线程同步等待 */
@Composable
private fun JmDomainEditor() {
    val scope = rememberCoroutineScope()
    var jmBase by remember { mutableStateOf("") }
    var savedTick by remember { mutableIntStateOf(0) }
    var baseError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        jmBase = SourcePrefs.current().jmBaseUrl ?: JmClient.DEFAULT_BASE
    }
    // "已保存"提示 2 秒后自动消失
    LaunchedEffect(savedTick) {
        if (savedTick > 0) {
            delay(2000)
            savedTick = 0
        }
    }

    Column {
        OutlinedTextField(
            value = jmBase,
            onValueChange = { jmBase = it },
            singleLine = true,
            label = { Text("禁漫 API 域名") },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
        ) {
            TextButton(onClick = { jmBase = JmClient.DEFAULT_BASE }) { Text("恢复默认") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    val v = jmBase.trim().trimEnd('/')
                    if (v.isEmpty()) {
                        // 此前输入为空时点击保存毫无反应，用户不知为何
                        baseError = "域名不能为空"
                        savedTick = 0
                        return@Button
                    }
                    baseError = null
                    scope.launch {
                        SourcePrefs.current().setJmBaseUrl(v)
                        // 回填规范化后的值，避免输入框仍显示带空格/尾斜杠的原文
                        jmBase = v
                        savedTick += 1
                    }
                },
            ) { Text("保存") }
        }
        if (savedTick > 0) {
            Text(
                text = "已保存",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
        baseError?.let { msg ->
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            )
        }
    }
}
