package com.pika.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.pika.core.source.SourceManager
import kotlinx.coroutines.launch

/**
 * 哔咔注册页：昵称 + 邮箱 + 密码 + 性别。
 * 注册成功自动登录（auth/register → auth/sign-in）。
 * 禁漫源不支持注册。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("m") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val unsupported = SourceManager.current().type == com.pika.core.source.SourceType.JMCOMIC

    fun submit() {
        if (name.isBlank() || email.isBlank() || password.isBlank() || confirm.isBlank()) {
            error = "请填写完整信息"
            return
        }
        if (name.length < 2) {
            error = "昵称长度至少 2 个字符"
            return
        }
        if (!Regex("^[a-zA-Z0-9]+$").matches(email)) {
            error = "用户名只能包含字母和数字（将作为登录邮箱）"
            return
        }
        if (password.length < 8) {
            error = "密码长度至少 8 个字符"
            return
        }
        if (password != confirm) {
            error = "两次密码不一致"
            return
        }
        loading = true
        error = null
        scope.launch {
            try {
                SourceManager.current().register(
                    email = email.trim(),
                    password = password,
                    name = name.trim(),
                    gender = gender,
                )
                onLoggedIn()
            } catch (e: Exception) {
                error = when {
                    e.message?.contains("already exist", ignoreCase = true) == true -> "该用户名已被注册"
                    e.message?.contains("429", ignoreCase = true) == true -> "请求过于频繁，请稍后再试"
                    else -> e.message ?: "注册失败"
                }
            } finally {
                loading = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("注册哔咔账号") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            if (unsupported) {
                Text(
                    text = "当前源（禁漫）不支持注册",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("昵称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("用户名（字母数字，作为登录邮箱）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码（至少 8 位）") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { confirm = it },
                label = { Text("确认密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = gender == "m",
                    onClick = { gender = "m" },
                    label = { Text("男") },
                )
                FilterChip(
                    selected = gender == "f",
                    onClick = { gender = "f" },
                    label = { Text("女") },
                )
                FilterChip(
                    selected = gender == "bot",
                    onClick = { gender = "bot" },
                    label = { Text("机器人") },
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
                onClick = { submit() },
                enabled = !loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("注册并登录")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "注册即同意哔咔平台规则；用户名将作为登录账号使用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
