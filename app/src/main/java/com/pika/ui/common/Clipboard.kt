package com.pika.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/**
 * 写入纯文本剪贴板。
 *
 * 统一做 trim 与空值保护：空文本不写入，避免把空字符串覆盖掉用户剪贴板。
 * 返回 false 时调用方提示复制失败，不抛出异常。
 */
fun Context.copyPlainText(label: String, text: String): Boolean {
    val value = text.trim()
    if (value.isEmpty()) return false
    return try {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        true
    } catch (e: Exception) {
        Log.w("Clipboard", "复制失败: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

/**
 * 复制文本并在结果出来后用 Snackbar 反馈。
 * 连续复制时先收起上一条，保证提示不排队堆叠。
 */
fun interface ClipboardCopier {
    fun copy(label: String, text: String, successMessage: String)
}

@Composable
fun rememberClipboardCopier(snackbarHostState: SnackbarHostState): ClipboardCopier {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope, snackbarHostState) {
        ClipboardCopier { label, text, successMessage ->
            val copied = context.copyPlainText(label, text)
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(
                    if (copied) successMessage else "复制失败，请重试",
                )
            }
        }
    }
}
