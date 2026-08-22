package com.pika.ui.mine

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.pika.core.model.ComicUser
import com.pika.core.source.SourceManager
import kotlinx.coroutines.launch

@Composable
fun MineScreen(
    onOpenSettings: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onOpenFavourites: () -> Unit = {},
    onOpenAuthorFavourites: () -> Unit = {},
    onOpenFollowManage: () -> Unit = {},
    onOpenReader: (String, Int) -> Unit = { _, _ -> },
    onOpenProfile: () -> Unit = {},
    onOpenMyComments: () -> Unit = {},
    onOpenRecentReads: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val activeSource by SourceManager.activeSource.collectAsState()
    val unauthorizedTick by SourceManager.unauthorizedTick.collectAsState()
    val loggedIn = SourceManager.current().isLoggedIn
    var user by remember { mutableStateOf<ComicUser?>(null) }
    var profileLoading by remember { mutableStateOf(false) }

    LaunchedEffect(loggedIn, unauthorizedTick) {
        if (loggedIn) {
            profileLoading = true
            try {
                user = SourceManager.current().profile()
            } catch (e: Exception) {
                // ignore
            } finally {
                profileLoading = false
            }
        } else {
            user = null
        }
    }

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
            if (loggedIn && profileLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            } else if (loggedIn && user != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProfile)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(CircleShape),
                    ) {
                        user?.avatarUrl?.let {
                            AsyncImage(
                                model = it,
                                contentDescription = "头像",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = user?.name ?: "用户",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Lv.${user?.level ?: 0} · ${user?.title ?: ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
            } else {
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
        }
        Spacer(Modifier.height(16.dp))
        MenuRow("收藏", onClick = onOpenFavourites)
        MenuRow("收藏的作者", onClick = onOpenAuthorFavourites)
        MenuRow("关注管理", onClick = onOpenFollowManage)
        MenuRow("我的评论", onClick = onOpenMyComments)
        MenuRow("阅读历史", onClick = onOpenRecentReads)
        MenuRow("下载", onClick = onOpenDownloads)
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
