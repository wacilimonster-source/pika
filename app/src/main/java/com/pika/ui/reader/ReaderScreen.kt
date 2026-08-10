package com.pika.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage

/** 阅读器：横滑翻页 + 顶部/底部栏（点按切换） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    comicId: String,
    order: Int,
    onBack: () -> Unit,
    title: String = "",
    viewModel: ReaderViewModel = viewModel(),
) {
    val pages by viewModel.pages.collectAsState()
    val epTitle by viewModel.epTitle.collectAsState()
    val loading by viewModel.loading.collectAsState()
    var showBars by remember { mutableStateOf(true) }

    val pagerState = rememberPagerState(pageCount = { pages.size })

    LaunchedEffect(comicId, order) {
        if (pages.isEmpty()) viewModel.load(comicId, order)
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            if (showBars) {
                TopAppBar(
                    title = {
                        Text(
                            text = title.ifBlank { epTitle }.ifBlank { "第 $order 话" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (showBars) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                ) {
                    Text(
                        text = if (loading) {
                            if (pages.isEmpty()) "加载中…" else "加载中…"
                        } else {
                            "${pagerState.currentPage + 1} / ${pages.size}"
                        },
                        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        },
    ) { innerPadding ->
        when {
            pages.isEmpty() && loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    LinearProgressIndicator()
                }
            }
            pages.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "加载失败",
                        color = Color.White,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            else -> {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { showBars = !showBars })
                        },
                ) { page ->
                    AsyncImage(
                        model = pages[page].imageUrl,
                        contentDescription = "第 ${page + 1} 页",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}