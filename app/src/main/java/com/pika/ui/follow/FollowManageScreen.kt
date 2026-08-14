package com.pika.ui.follow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.pika.data.FollowSettings

/** 关注管理：添加/删除关键词关注（支持组合关键词，且关系） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowManageScreen(
    onBack: () -> Unit,
) {
    var items by remember { mutableStateOf(FollowSettings.items()) }
    var showKeywordDialog by remember { mutableStateOf(false) }

    fun reload() {
        items = FollowSettings.items()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关注管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
        ) {
            item {
                SectionLabel(
                    title = "关键词关注",
                    actionLabel = "添加",
                    onAction = { showKeywordDialog = true },
                )
            }
            if (items.isEmpty()) {
                item { EmptyHint("暂无关键词关注，点击添加") }
            }
            items(items, key = { it.keywords.joinToString("+") }) { item ->
                FollowRow(
                    name = item.keywords.joinToString(" + "),
                    onDelete = {
                        FollowSettings.removeItem(item.createdAt)
                        reload()
                    },
                )
            }
        }
    }

    if (showKeywordDialog) {
        KeywordAddDialog(
            onDismiss = { showKeywordDialog = false },
            onAdd = { keywords ->
                FollowSettings.addItem(keywords)
                showKeywordDialog = false
                reload()
            },
        )
    }
}

@Composable
private fun SectionLabel(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onAction, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 2.dp))
            Text(actionLabel)
        }
    }
}

@Composable
private fun FollowRow(name: String, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = "删除")
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KeywordAddDialog(onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加关键词关注") },
        text = {
            Column {
                Text(
                    text = "多个关键词用空格分隔，作品标题/标签需同时包含全部关键词（且关系），如：校园 热血",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("输入关键词，多个用空格分隔") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        val words = parseWords(input)
                        if (words.isNotEmpty()) onAdd(words)
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(parseWords(input)) },
                enabled = parseWords(input).isNotEmpty(),
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun parseWords(input: String): List<String> =
    input.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()