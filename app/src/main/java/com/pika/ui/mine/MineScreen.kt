package com.pika.ui.mine

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pika.core.source.SourceManager
import kotlinx.coroutines.launch

/**
 * 我的：账号（当前源登录态）/ 下载 / 设置 / 签到。
 */
@Composable
fun MineScreen(
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenFavourites: () -> Unit = {},
    onOpenReader: (String, Int) -> Unit = { _, _ -> },
) {
    val scope = rememberCoroutineScope()
    val activeSource by SourceManager.activeSource.collectAsState()
    val loggedIn = SourceManager.current().isLoggedIn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "PiKA",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(16.dp))
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    text = "数据源 · ${activeSource.displayName}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (loggedIn) "已登录" else "未登录",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        MenuRow("收藏", onClick = onOpenFavourites)
        MenuRow("阅读历史") {}
        MenuRow("下载", onClick = onOpenDownloads)
        MenuRow("每日签到") {}
        MenuRow("设置", onClick = onOpenSettings)
        if (loggedIn) {
            MenuRow("退出登录") {
                scope.launch {
                    SourceManager.onUnauthorized()
                }
            }
        }
    }
}

@Composable
private fun MenuRow(
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
    }
    HorizontalDivider()
}