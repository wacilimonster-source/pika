package com.pika.ui.login

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.pika.core.source.SourceManager
import kotlinx.coroutines.launch

/**
 * 登录页：按当前源展示对应登录表单。
 * 哔咔：邮箱 + 密码（auth/sign-in），附注册入口
 * 禁漫：邮箱 + 密码（v3 sign-in，换取 token）
 */
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onOpenRegister: () -> Unit = {},
    onOpenForgotPassword: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val activeSource by SourceManager.activeSource.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 密码默认掩码显示，可点眼睛临时查看
    var passwordVisible by remember { mutableStateOf(false) }
    // 是否保存账号密码（全局偏好，默认沿用既有行为 = 保存）
    var rememberPassword by remember { mutableStateOf(com.pika.data.SecureAccountStore.saveEnabled) }
    // 已保存账号（邮箱仅用于展示，非敏感）
    var savedEmail by remember(activeSource) { mutableStateOf(SourceManager.savedAccountEmail()) }
    LaunchedEffect(activeSource) {
        // 邮箱不涉及敏感信息，直接回填；密码仅在「保存账号密码」开启时回填
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.pika.data.SecureAccountStore.load(activeSource)
        }
        if (saved != null) {
            email = saved.first
            if (rememberPassword) password = saved.second
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .imePadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "登录 ${activeSource.displayName}",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (savedEmail != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已保存账号：$savedEmail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("账号邮箱") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = rememberPassword,
                onCheckedChange = { checked ->
                    rememberPassword = checked
                    com.pika.data.SecureAccountStore.saveEnabled = checked
                    if (!checked) {
                        // 关闭即清除已保存凭据，避免"以为没存"却仍留在磁盘/Keystore
                        com.pika.data.SecureAccountStore.clear(activeSource)
                        savedEmail = null
                    } else {
                        savedEmail = SourceManager.savedAccountEmail()
                    }
                },
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
                        error = e.message ?: "登录失败"
                    } finally {
                        loading = false
                    }
                }
            },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
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
        if (activeSource == com.pika.core.source.SourceType.PICACG) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onOpenRegister,
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
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