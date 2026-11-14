package com.pika.ui.login

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pika.core.log.LogStore
import com.pika.core.source.SourceManager
import com.pika.core.source.SourceType
import com.pika.data.SecureAccountStore
import com.pika.network.PicaException
import kotlinx.coroutines.launch

/**
 * 登录页：邮箱 + 密码（auth/sign-in），附注册入口。
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onOpenRegister: () -> Unit = {},
    onOpenForgotPassword: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val activeSource by SourceManager.activeSource.collectAsState()
    // 邮箱用 rememberSaveable：页面切换/配置变化（横竖屏、深浅色、字体缩放）后不丢（S-5.8）；
    // 密码出于安全考虑不跨配置保留，重新从凭据库回填
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 密码默认掩码显示，可点眼睛临时查看
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    // 是否保存账号密码（全局偏好，默认沿用既有行为 = 保存）
    var rememberPassword by remember { mutableStateOf(SecureAccountStore.saveEnabled) }
    // 已保存账号（邮箱仅用于展示，非敏感）
    var savedEmail by remember(activeSource) { mutableStateOf(SourceManager.savedAccountEmail()) }
    val configuration = LocalConfiguration.current
    val fontScale = LocalDensity.current.fontScale
    // S-5.7：横屏或字体放大时首屏高度吃紧，压缩标题与间距，
    // 保证「登录」主操作在首屏内可见（实测横屏 + 1.3 时按钮曾被折叠线挡住）
    val compact = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        fontScale >= 1.3f
    // S-5.8：userEdited 也要跨配置保留——配置变化后若被复位为 false，
    // 回填协程会把已保存的旧账号覆盖到用户刚输入一半的邮箱上
    var userEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(activeSource) {
        // 邮箱不涉及敏感信息，直接回填；密码仅在「保存账号密码」开启时回填
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            SecureAccountStore.load(activeSource)
        }
        if (saved != null && !userEdited) {
            // 双保险：即便未 userEdited，也只填充当前为空的字段，绝不覆盖已有输入
            if (email.isBlank()) email = saved.first
            if (rememberPassword && password.isBlank()) password = saved.second
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(if (compact) 16.dp else 24.dp)
            .imePadding()
            // 键盘弹出时内容可能超出可视高度（小屏/字体放大场景），必须可滚动，
            // 否则密码框/登录按钮被键盘裁掉且无法滚到（BUG-22）
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "登录 ${activeSource.displayName}",
            style = if (compact) MaterialTheme.typography.titleLarge
            else MaterialTheme.typography.headlineSmall,
        )
        if (savedEmail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已保存账号：$savedEmail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(if (compact) 8.dp else 24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = {
                userEdited = true
                email = it
            },
            label = { Text("账号邮箱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = {
                userEdited = true
                password = it
            },
            label = { Text("密码") },
            singleLine = true,
            // 默认掩码：此前缺失该属性，打开登录页即明文显示已保存密码（截图/旁人可见）
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                        else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // 整个「复选框 + 说明文字」都可点：真机实测发现点文字无反应，
        // 用户会以为开关坏了（小屏上复选框本身只有 ~20dp 命中区）。
        val toggleSave: (Boolean) -> Unit = { checked ->
            rememberPassword = checked
            SecureAccountStore.saveEnabled = checked
            if (!checked) {
                // 关闭即清除已保存凭据，避免“以为没存”却仍留在磁盘/Keystore
                SecureAccountStore.clear(activeSource)
                savedEmail = null
            } else {
                savedEmail = SourceManager.savedAccountEmail()
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { toggleSave(!rememberPassword) },
        ) {
            Checkbox(
                checked = rememberPassword,
                onCheckedChange = toggleSave,
            )
            Text(
                text = "保存账号密码（便于下次一键登录）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))

        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
        }

        Button(
            onClick = {
                if (email.isBlank() || password.isBlank()) {
                    error = "请输入账号和密码"
                    return@Button
                }
                loading = true
                error = null
                scope.launch {
                    try {
                        SourceManager.current().login(email.trim(), password)
                        onLoggedIn()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // S-5.1：只展示映射后的可读文案；原始异常（含服务端响应细节）进日志
                        LogStore.log("Auth", "E", "登录失败：${e.javaClass.simpleName}: ${e.message}")
                        error = (e as? PicaException)?.message ?: "登录失败，请稍后重试"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("登录")
            }
        }
        if (activeSource == SourceType.PICACG) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenRegister,
                enabled = !loading,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text("注册哔咔账号")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "忘记密码？",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable(onClick = onOpenForgotPassword),
            )
        }
    }
}