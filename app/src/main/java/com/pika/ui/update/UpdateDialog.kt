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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    // 不能用固定路径猜（此前猜 cacheDir，而下载实际落在 filesDir，导致 apkFile.exists()
    // 恒为 false、点「立即安装」被静默关窗）。改为记住 downloadAndVerify 的返回值。
    var apkFile by remember { mutableStateOf<java.io.File?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
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
                    if (totalBytes > 0) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        // 总长未知：按已下载字节数展示
                        Text(
                            text = "已下载 %.1f MB…".format(downloadedBytes / 1024f / 1024f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    val f = apkFile
                    when {
                        f == null || !f.exists() ->
                            error = "安装包丢失，请重新下载"
                        !UpdateManager.install(context, f) ->
                            error = "安装失败，请手动打开 APK"
                        else -> onDismiss()
                    }
                }) { Text("立即安装") }

                downloading -> TextButton(onClick = {}, enabled = false) { Text("下载中…") }

                else -> Button(onClick = {
                    downloading = true
                    error = null
                    scope.launch {
                        // runCatchingCancellable：弹窗关闭导致的取消不能被当成"下载失败"
                        com.pika.core.runCatchingCancellable {
                            // 下载 + SHA-256 校验：update.json 提供 sha256 时强校验
                            UpdateManager.downloadAndVerify(context, info) { p, done, total ->
                                progress = p
                                downloadedBytes = done
                                totalBytes = total
                            }
                        }.onSuccess { apk ->
                            apkFile = apk          // 记住真实路径，安装时不再猜
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
