package com.pika.ui.follow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

/** 关注管理：添加/删除关键词与分类标签关注 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowManageScreen(
    onBack: () -> Unit,
) {
    var keywords by remember { mutableStateOf(FollowSettings.keywords()) }
    var categories by remember { mutableStateOf(FollowSettings.categories()) }
    var showKeywordDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }

    fun reload() {
        keywords = FollowSettings.keywords()
        categories = FollowSettings.categories()
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
            if (keywords.isEmpty()) {
                item { EmptyHint("暂无关键词关注") }
            }
            items(keywords, key = { "k_$it" }) { keyword ->
                FollowRow(
                    name = keyword,
                    onDelete = {
                        FollowSettings.removeKeyword(keyword)
                        reload()
                    },
                )
            }

            item {
                SectionLabel(
                    title = "分类标签关注",
                    actionLabel = "添加",
                    onAction = { showCategoryDialog = true },
                )
            }
            if (categories.isEmpty()) {
                item { EmptyHint("暂无分类标签关注") }
            }
            items(categories, key = { "c_${it.id}" }) { category ->
                FollowRow(
                    name = category.title,
                    onDelete = {
                        FollowSettings.removeCategory(category.id)
                        reload()
                    },
                )
            }
        }
    }

    if (showKeywordDialog) {
        KeywordAddDialog(
            onDismiss = { showKeywordDialog = false },
            onAdd = { keyword ->
                FollowSettings.addKeyword(keyword)
                showKeywordDialog = false
                reload()
            },
        )
    }

    if (showCategoryDialog) {
        CategoryAddDialog(
            onDismiss = { showCategoryDialog = false },
            onAdd = { id, title ->
                FollowSettings.addCategory(id, title)
                showCategoryDialog = false
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
private fun KeywordAddDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加关键词关注") },
        text = {
            Column {
                Text(
                    text = "关注后首页将展示该关键词的最新漫画，如作者名、作品系列名等",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    placeholder = { Text("输入关键词") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (input.isNotBlank()) onAdd(input.trim())
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(input.trim()) },
                enabled = input.isNotBlank(),
            ) { Text("添加") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun CategoryAddDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String) -> Unit,
) {
    var categories by remember { mutableStateOf<List<com.pika.core.model.ComicCategory>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        loading = true
        error = null
        try {
            categories = com.pika.core.source.SourceManager.current().categories()
        } catch (e: Exception) {
            error = e.message ?: "加载分类失败"
        } finally {
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加分类标签关注") },
        text = {
            Column {
                Text(
                    text = "关注后首页将展示该分类的最新漫画",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when {
                    loading -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text("加载中...", style = MaterialTheme.typography.bodyMedium)
                    }
                    error != null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(error ?: "", style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp),
                    ) {
                        items(categories, key = { it.id }) { category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAdd(category.id, category.title)
                                    }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    text = category.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}