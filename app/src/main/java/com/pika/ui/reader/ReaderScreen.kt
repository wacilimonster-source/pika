package com.pika.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pika.data.ReaderPrefs
import kotlinx.coroutines.launch

/**
 * 阅读器（重构版）：
 *
 * - **滚动流（条漫，默认）**：LazyColumn 纵向滚动；超长图自动切分为多屏，贴合竖屏。
 * - **横滑翻页**：HorizontalPager + 双击缩放。
 * - **手势**：点按左/右边缘翻页（滚动流为翻屏）、中间点按唤出控制面板、双击放大（翻页模式）。
 * - **预加载**：可见页变化时预取前后 2 页，弱网也顺滑。
 * - **进度**：本地保存（order+页码），重进自动续读；切后台自动保存。
 * - **控制面板**：阅读模式切换、亮度滑条、上一话/下一话。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    comicId: String,
    order: Int,
    onBack: () -> Unit,
    title: String = "",
    viewModel: ReaderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pages by viewModel.pages.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val epTitle by viewModel.epTitle.collectAsState()
    val loading by viewModel.loading.collectAsState()

    var scrollMode by remember { mutableStateOf(ReaderPrefs.current().readerMode == 0) }
    var showPanel by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(ReaderPrefs.current().brightness) }
    var zoomScale by remember { mutableFloatStateOf(1f) }

    val configuration = LocalConfiguration.current
    val viewportAspect = remember(configuration) {
        if (configuration.screenWidthDp <= 0) 1.8f
        else (configuration.screenHeightDp.toFloat() / configuration.screenWidthDp.toFloat())
            .coerceAtLeast(0.1f)
    }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // 加载章节
    LaunchedEffect(comicId, order) {
        viewModel.load(context, comicId, order)
    }

    // 进度恢复：pages 就绪后跳到上次阅读位置
    val restorePage = viewModel.pendingRestorePage
    LaunchedEffect(pages.size, restorePage, scrollMode) {
        if (pages.isNotEmpty() && restorePage >= 0) {
            val target = restorePage.coerceAtMost(pages.size - 1)
            if (scrollMode) {
                listState.scrollToItem(target)
            } else {
                pagerState.scrollToPage(target)
            }
            viewModel.pendingRestorePage = -1
        }
    }

    // ── 滚动流：页 → 行 平铺（长图分割） ──────────────────────────────────
    val sliceCounts = remember { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(scrollMode) {
        if (!scrollMode) sliceCounts.clear()
    }
    val rows: List<Pair<Int, Int>> = if (scrollMode) {
        buildList {
            for (p in pages.indices) {
                val n = (sliceCounts[p] ?: 1).coerceAtLeast(1)
                repeat(n) { s -> add(p to s) }
            }
        }
    } else {
        pages.indices.map { it to 0 }
    }

    fun rowToPage(row: Int): Int {
        if (pages.isEmpty()) return 0
        var remaining = row.coerceAtLeast(0)
        for (p in pages.indices) {
            val n = (sliceCounts[p] ?: 1).coerceAtLeast(1)
            if (remaining < n) return p
            remaining -= n
        }
        return pages.size - 1
    }

    // 滚动流当前页：监听 firstVisibleItemIndex（snapshotFlow）
    val scrollVisiblePage = remember { mutableIntStateOf(0) }
    LaunchedEffect(listState, pages.size) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { row -> scrollVisiblePage.value = rowToPage(row) }
    }
    val currentPage = if (scrollMode) {
        scrollVisiblePage.value.coerceAtMost((pages.size - 1).coerceAtLeast(0))
    } else {
        pagerState.currentPage
    }

    // 保存进度：页面变化时 + 切后台时；同时刷新"最近阅读"
    LaunchedEffect(currentPage, pages.size) {
        if (pages.isNotEmpty()) {
            viewModel.saveProgress(currentPage)
            viewModel.recordRecentRead(epTitle, viewModel.coverUrl.value, currentPage)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.saveProgress(currentPage)
    }

    // 预加载前后 2 页
    LaunchedEffect(currentPage) {
        if (pages.isNotEmpty()) viewModel.preloadNearby(context, currentPage, range = 2)
    }

    // ── 切章 ──────────────────────────────────────────────────────────────
    val sortedChapters = remember(chapters) { chapters.sortedBy { it.order } }
    val currentChapterIndex = sortedChapters.indexOfFirst { it.order == order }
    fun switchTo(targetOrder: Int) {
        showPanel = false
        viewModel.switchChapter(context, targetOrder)
    }

    // 点按：左 30% 上翻 / 右 30% 下翻 / 中间 40% 面板
    fun onTap(offsetX: Float, width: Int) {
        val zone = when {
            offsetX < width * 0.3f -> -1
            offsetX > width * 0.7f -> 1
            else -> 0
        }
        when {
            zone == 0 -> showPanel = !showPanel
            scrollMode -> {
                val viewportHeight = listState.layoutInfo.viewportSize.height
                if (viewportHeight > 0) {
                    scope.launch { listState.animateScrollBy(viewportHeight * 0.9f * zone) }
                }
            }
            else -> {
                val target = (pagerState.currentPage + zone)
                    .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                scope.launch { pagerState.animateScrollToPage(target) }
            }
        }
    }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            AnimatedVisibility(visible = showPanel) {
                TopAppBar(
                    title = {
                        Text(
                            text = title.ifBlank { epTitle }.ifBlank { "第 $order 话" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = Color.White,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = Color.White,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xCC000000)),
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(visible = showPanel) {
                ReaderControlPanel(
                    scrollMode = scrollMode,
                    onModeChange = { mode ->
                        scrollMode = mode
                        ReaderPrefs.current().readerMode = if (mode) 0 else 1
                        if (mode) {
                            scope.launch {
                                listState.scrollToItem(rowToPage(pagerState.currentPage))
                            }
                        }
                    },
                    brightness = brightness,
                    onBrightnessChange = {
                        brightness = it
                        ReaderPrefs.current().brightness = it
                    },
                    currentPage = currentPage + 1,
                    totalPages = pages.size,
                    hasPrev = currentChapterIndex > 0,
                    hasNext = currentChapterIndex in 0 until sortedChapters.size - 1,
                    onPrevChapter = {
                        sortedChapters.getOrNull(currentChapterIndex - 1)?.let { switchTo(it.order) }
                    },
                    onNextChapter = {
                        sortedChapters.getOrNull(currentChapterIndex + 1)?.let { switchTo(it.order) }
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset -> onTap(offset.x, size.width) },
                        onDoubleTap = {
                            if (!scrollMode) {
                                zoomScale = if (zoomScale > 1f) 1f else 2f
                            }
                        },
                    )
                },
        ) {
            when {
                pages.isEmpty() && loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                pages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "加载失败，点击返回重试",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                scrollMode -> {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        items(
                            count = rows.size,
                            key = { index ->
                                val (p, s) = rows[index]
                                "row_${p}_$s"
                            },
                        ) { index ->
                            val (pageIndex, sliceIndex) = rows[index]
                            WebtoonSplitPage(
                                pageIndex = pageIndex,
                                imageUrl = pages[pageIndex].imageUrl,
                                sliceIndex = sliceIndex,
                                sliceCount = sliceCounts[pageIndex] ?: 1,
                                viewportAspect = viewportAspect,
                                splitEnabled = true,
                                isPrimary = sliceIndex == 0,
                                onSliceCountResolved = { p, n -> sliceCounts[p] = n },
                            )
                        }
                    }
                }

                else -> {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
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

            // 亮度蒙层
            if (brightness < 1f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = (1f - brightness).coerceIn(0f, 0.8f))),
                )
            }
        }
    }
}

/** 阅读器底部控制面板：模式切换 / 亮度 / 上一话·下一话 / 页码 */
@Composable
private fun ReaderControlPanel(
    scrollMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    currentPage: Int,
    totalPages: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    Surface(color = Color(0xE6000000)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.BrightnessMedium,
                    contentDescription = "亮度",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Slider(
                    value = brightness,
                    onValueChange = onBrightnessChange,
                    valueRange = 0.2f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(brightness * 100).toInt()}%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onModeChange(false) }) {
                    Icon(
                        Icons.Filled.ViewCarousel,
                        contentDescription = "横滑翻页",
                        tint = if (scrollMode) Color.Gray else Color.White,
                    )
                }
                Text(
                    text = if (scrollMode) "滚动流" else "翻页",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
                IconButton(onClick = { onModeChange(true) }) {
                    Icon(
                        Icons.Filled.ViewAgenda,
                        contentDescription = "滚动流",
                        tint = if (scrollMode) Color.White else Color.Gray,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onPrevChapter, enabled = hasPrev) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一话",
                        tint = if (hasPrev) Color.White else Color.Gray,
                    )
                }
                Text(
                    text = "$currentPage / $totalPages",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = onNextChapter, enabled = hasNext) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一话",
                        tint = if (hasNext) Color.White else Color.Gray,
                    )
                }
            }
        }
    }
}
