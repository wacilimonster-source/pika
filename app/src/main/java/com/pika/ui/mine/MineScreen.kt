package com.pika.ui.mine

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    onOpenMyComments: () -> Unit = {},
    onOpenReader: (String, Int) -> Unit = { _, _ -> },
    onOpenProfile: () -> Unit = {},
    onOpenRecentReads: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val activeSource by SourceManager.activeSource.collectAsState()
    val unauthorizedTick by SourceManager.unauthorizedTick.collectAsState()
    val loggedIn = SourceManager.current().isLoggedIn
    // 更新红点：有新版本且未被「稍后再说」/关闭时，设置入口带红点（D5）
    val updateAvailable by com.pika.core.update.UpdateState.updateInfo.collectAsState()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var deleteSavedCredentials by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf<ComicUser?>(null) }
    var profileLoading by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    // 重试计数：+1 触发下方 LaunchedEffect 重拉资料（失败态不再是死胡同，BUG-23）
    var profileRetryTick by remember { mutableStateOf(0) }

    LaunchedEffect(loggedIn, unauthorizedTick, profileRetryTick) {
        if (loggedIn) {
            profileLoading = true
            profileError = null
            try {
                user = SourceManager.current().profile()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 此前完全静默：用户只看到空白资料卡，无从判断是没登录还是请求失败
                profileError = e.message ?: "资料加载失败"
            } finally {
                profileLoading = false
            }
        } else {
            user = null
            profileError = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = "我的",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 12.dp),
        )
        Spacer(Modifier.height(16.dp))
        // 资料卡：头像 + 昵称 + 等级称号（真实数据），未登录时显示数据源与登录态
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            when {
                loggedIn && profileLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
                loggedIn && user == null && profileError != null -> {
                    // 加载失败给出可读提示 + 重试（此前只显示一行错误文字，只能退出重进）
                    com.pika.ui.common.ErrorRetry(
                        message = "资料加载失败：$profileError",
                        onRetry = { profileRetryTick++ },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                    )
                }
                loggedIn && user != null -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenProfile)
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
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
                        Spacer(Modifier.width(14.dp))
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
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
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
        }
        Spacer(Modifier.height(20.dp))

        // 菜单分组：组头标签 + 圆角组卡 + 图标行（8 项入口全部保留，按登录态展示）
        MenuGroupHeader("我的收藏与关注")
        MenuGroup {
            MenuRow("收藏的作品", Icons.Outlined.Favorite, onClick = onOpenFavourites)
            MenuRowDivider()
            MenuRow("收藏的作者", Icons.Outlined.Person, onClick = onOpenAuthorFavourites)
            MenuRowDivider()
            MenuRow("关注管理", Icons.Outlined.Notifications, onClick = onOpenFollowManage)
            if (loggedIn) {
                MenuRowDivider()
                MenuRow("我的评论", Icons.Outlined.ChatBubbleOutline, onClick = onOpenMyComments)
            }
        }
        MenuGroupHeader("阅读与离线")
        MenuGroup {
            MenuRow("阅读历史", Icons.Outlined.History, onClick = onOpenRecentReads)
            MenuRowDivider()
            MenuRow("下载", Icons.Outlined.Download, onClick = onOpenDownloads)
        }
        MenuGroupHeader("通用")
        MenuGroup {
            MenuRow(
                "设置",
                Icons.Outlined.Settings,
                onClick = onOpenSettings,
                showBadge = updateAvailable != null,
            )
            if (loggedIn) {
                MenuRowDivider()
                MenuRow("退出登录", Icons.AutoMirrored.Outlined.Logout, onClick = { showLogoutDialog = true })
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showLogoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = {
                Column {
                    Text("确定退出当前账号吗？")
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteSavedCredentials,
                            onCheckedChange = { deleteSavedCredentials = it },
                        )
                        Text(
                            "同时删除保存的账号密码",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutDialog = false
                    scope.launch {
                        SourceManager.logout(deleteSavedCredentials)
                    }
                }) { Text("退出") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            },
        )
    }
}

/** 组头标签（小写间距样式，与设置页组头一致） */
@Composable
private fun MenuGroupHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

/** 菜单分组容器：圆角组卡（radius 14，design-tokens groupRadius） */
@Composable
private fun MenuGroup(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
    ) {
        Column { content() }
    }
}

/** 组内行分割线：左侧内缩到图标之后 */
@Composable
private fun MenuRowDivider() {
    HorizontalDivider(Modifier.padding(start = 60.dp))
}

@Composable
private fun MenuRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    /** 尾部红点（如「设置」行的新版本提醒） */
    showBadge: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标底座：小圆角方块（视觉分组），点击区由整行承担
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (showBadge) {
            Box(
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(8.dp)
                    .background(
                        MaterialTheme.colorScheme.error,
                        CircleShape,
                    ),
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}
