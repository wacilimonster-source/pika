package com.pika.ui.update

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pika.core.update.UpdateManager
import kotlinx.coroutines.launch

/**
 * 版本更新对话框：显示更新说明 + 下载进度 + 安装。
 * 供首页更新横幅 / 设置页复用。
 */
@Composable
fun UpdateDialog(
    info: UpdateManager.UpdateInfo,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apkFile = remember { java.io.File(context.cacheDir, "pika-update.apk") }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("发现新版本 v${info.version}") },
        text = {
            Column {
                Text(
                    text = info.notes.ifBlank { "修复已知问题，提升体验" },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            when {
                downloaded -> Button(onClick = {
                    if (apkFile.exists() && !UpdateManager.install(context, apkFile)) {
                        error = "安装失败，请手动打开 APK"
                    } else {
                        onDismiss()
                    }
                }) { Text("立即安装") }

                downloading -> TextButton(onClick = {}, enabled = false) { Text("下载中…") }

                else -> Button(onClick = {
                    downloading = true
                    error = null
                    scope.launch {
                        runCatching {
                            UpdateManager.download(context, info.apkUrl) { p -> progress = p }
                        }.onSuccess {
                            downloaded = true
                            downloading = false
                        }.onFailure { e ->
                            downloading = false
                            error = "下载失败：${e.message}"
                        }
                    }
                }) { Text("下载") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!downloading) onDismiss() }) {
                Text(if (downloaded) "稍后安装" else "取消")
            }
        },
    )
}
