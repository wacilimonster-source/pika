package com.pika.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pika.core.source.SourceManager
import com.pika.core.source.SourceType
import com.pika.data.SecureAccountStore

/**
 * 数据源管理二级页：源账号状态与登录入口。
 * 登出等账号操作仍在"我的"页，此处只负责登录入口与源级配置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManageScreen(
    onBack: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val activeSource by SourceManager.activeSource.collectAsState()
    // 登录态本身非响应式：401/登出会自增 tick，借此重查登录状态
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
                        onLogin = onOpenLogin,
                    )
                }
            }
        }
    }
}

/** 账号状态行：未登录显示"去登录"，已登录提供"重新登录"（登录页自动填充已存凭据） */
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
                isCurrent -> TextButton(onClick = onLogin) { Text("重新登录") }
            }
        },
    )
}
