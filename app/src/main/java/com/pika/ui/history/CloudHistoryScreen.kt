package com.pika.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.pika.core.model.ComicSummary
import com.pika.core.source.SourceManager
import com.pika.ui.browse.ComicGridView
import kotlinx.coroutines.launch

/**
 * 云端浏览历史（禁漫源独有）。逻辑镜像 FavouriteScreen，但数据来自
 * SourceManager.current().cloudHistory(page)（需登录）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudHistoryScreen(
    onBack: () -> Unit,
    onComicClick: (String) -> Unit = {},
) {
    var comics by remember { mutableStateOf<List<ComicSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    val listState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    fun load(next: Int) {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            try {
                val r = SourceManager.current().cloudHistory(next)
                comics = if (next == 1) r.items else comics + r.items
                page = next
                endReached = next >= r.pages
            } catch (e: Exception) {
                if (comics.isEmpty()) error = e.message ?: "加载失败"
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { load(1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云端历史") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                windowInsets = WindowInsets(0, 0),
            )
        },
    ) { innerPadding ->
        if (error != null && comics.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = error ?: "加载失败", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ComicGridView(
                    comics = comics,
                    loading = loading,
                    endReached = endReached,
                    listState = listState,
                    onLoadMore = { load(page + 1) },
                    onComicClick = onComicClick,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
