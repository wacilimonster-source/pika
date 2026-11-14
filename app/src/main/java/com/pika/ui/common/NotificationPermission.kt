package com.pika.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * 通知权限按需申请（S-5.3）：返回一个「确保通知权限」的启动函数。
 * - API < 33：无运行时权限，直接回调 granted = true；
 * - 已授予：直接回调 true，不弹系统对话框；
 * - 未授予：发起系统权限弹窗，授予结果经 [onResult] 回调。
 *
 * 供两处使用：设置页「下载完成通知」开关点击时、详情页首次发起下载时
 * （偏好默认开但权限默认未授予，若只在设置页申请，用户永远收不到完成通知）。
 */
@Composable
fun rememberNotificationPermissionRequester(
    onResult: (granted: Boolean) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }
    return {
        // Context.checkSelfPermission 为平台 API（minSdk 26 恒可用），不引 androidx.core
        val granted = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            onResult(true)
        } else {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
