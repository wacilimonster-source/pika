package com.pika.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.pika.core.model.ComicUser
import com.pika.core.source.SourceManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * 个人资料页：头像 / 昵称 / 等级 / 简介；支持修改简介、称号、头像、密码。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf<ComicUser?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showSloganDialog by remember { mutableStateOf(false) }
    var showTitleDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showAvatarDialog by remember { mutableStateOf(false) }

    var avatarBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val ctx = context.applicationContext
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes != null && bytes.size <= 5 * 1024 * 1024) {
                        loading = true
                        error = null
                        try {
                            SourceManager.current().updateAvatar(
                                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP),
                            )
                            avatarBitmap = withContext(Dispatchers.IO) {
                                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                            error = "头像更新成功"
                        } finally {
                            loading = false
                        }
                    } else {
                        error = "图片需小于 5MB"
                    }
                } catch (e: Exception) {
                    error = "头像更新失败：${e.message}"
                }
            }
        }
    }

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                user = SourceManager.current().profile()
            } catch (e: UnsupportedOperationException) {
                error = "当前源不支持个人资料"
            } catch (e: Exception) {
                error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("个人资料") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(Modifier.padding(innerPadding).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                val u = user
                if (u != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .clickable { showAvatarDialog = true },
                        ) {
                            if (avatarBitmap != null) {
                                Image(
                                    bitmap = avatarBitmap!!.asImageBitmap(),
                                    contentDescription = "头像",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            } else if (u.avatarUrl != null) {
                                AsyncImage(
                                    model = u.avatarUrl,
                                    contentDescription = "头像",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        Spacer(Modifier.size(16.dp))
                        Column {
                            Text(
                                text = u.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(
                                text = "Lv.${u.level} · ${u.title} · 经验 ${u.exp}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                    ProfileRow("邮箱", u.email.ifBlank { "--" })
                    ProfileRow("性别", when (u.gender) {
                        "m" -> "男"
                        "f" -> "女"
                        "bot" -> "机器人"
                        else -> "--"
                    })
                    ProfileRow("生日", u.birthday.ifBlank { "--" })
                    ProfileRow("称号", u.title.ifBlank { "--" }, onClick = { showTitleDialog = true })
                    ProfileRow("简介", u.slogan.ifBlank { "--" }, onClick = { showSloganDialog = true })
                    ProfileRow("修改密码", "点击修改", onClick = { showPasswordDialog = true })
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.contains("成功")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (loading) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.padding(12.dp))
                    }
                }
            }
        }
    }

    if (showSloganDialog) {
        EditTextDialog(
            title = "修改简介",
            initial = user?.slogan ?: "",
            onSubmit = { value ->
                showSloganDialog = false
                scope.launch {
                    loading = true
                    try {
                        SourceManager.current().updateSlogan(value.trim())
                        error = "简介更新成功"
                        user = user?.copy(slogan = value.trim())
                    } catch (e: Exception) {
                        error = "更新失败：${e.message}"
                    } finally {
                        loading = false
                    }
                }
            },
            onDismiss = { showSloganDialog = false },
        )
    }
    if (showTitleDialog) {
        EditTextDialog(
            title = "修改称号",
            initial = user?.title ?: "",
            onSubmit = { value ->
                showTitleDialog = false
                scope.launch {
                    loading = true
                    try {
                        SourceManager.current().updateTitle(value.trim())
                        error = "称号更新成功"
                        user = user?.copy(title = value.trim())
                    } catch (e: Exception) {
                        error = "更新失败：${e.message}"
                    } finally {
                        loading = false
                    }
                }
            },
            onDismiss = { showTitleDialog = false },
        )
    }
    if (showPasswordDialog) {
        ChangePasswordDialog(
            onSubmit = { old, new ->
                showPasswordDialog = false
                scope.launch {
                    loading = true
                    try {
                        SourceManager.current().updatePassword(old, new)
                        error = "密码修改成功"
                    } catch (e: Exception) {
                        error = "修改失败：${e.message}"
                    } finally {
                        loading = false
                    }
                }
            },
            onDismiss = { showPasswordDialog = false },
        )
    }
    if (showAvatarDialog) {
        AlertDialog(
            onDismissRequest = { showAvatarDialog = false },
            title = { Text("更换头像") },
            text = { Text("选择一张图片（≤5MB，JPEG）作为新头像") },
            confirmButton = {
                TextButton(onClick = {
                    showAvatarDialog = false
                    picker.launch("image/*")
                }) { Text("选择图片") }
            },
            dismissButton = {
                TextButton(onClick = { showAvatarDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ProfileRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (onClick != null) {
            Text(
                text = "编辑 ›",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun EditTextDialog(
    title: String,
    initial: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(value) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun ChangePasswordDialog(
    onSubmit: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改密码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = old,
                    onValueChange = { old = it },
                    label = { Text("旧密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = new,
                    onValueChange = { new = it },
                    label = { Text("新密码（至少 8 位）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("确认新密码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when {
                    old.isBlank() || new.isBlank() || confirm.isBlank() -> error = "请填写完整"
                    new.length < 8 -> error = "新密码至少 8 位"
                    new != confirm -> error = "两次密码不一致"
                    else -> onSubmit(old, new)
                }
            }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
