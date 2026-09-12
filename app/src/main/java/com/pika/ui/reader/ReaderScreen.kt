package com.pika.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.pika.data.ReaderPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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
@OptIn(ExperimentalMaterial3Api::class, kotlinx.coroutines.FlowPreview::class)
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
    // 缩放平移偏移：双击放大后允许拖动查看画面边缘（否则放大后只能看正中央）
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    val configuration = LocalConfiguration.current
    val viewportAspect = remember(configuration) {
        if (configuration.screenWidthDp <= 0) 1.8f
        else (configuration.screenHeightDp.toFloat() / configuration.screenWidthDp.toFloat())
            .coerceAtLeast(0.1f)
    }

    val listState = rememberLazyListState()
    val pagerState = rememberPagerState(pageCount = { pages.size })

    // ── 沉浸式全屏管理 ─────────────────────────────────────────────────────
    val view = LocalView.current
    val windowInsetsController = remember {
        val activity = (context as? android.app.Activity) ?: return@remember null
        WindowCompat.getInsetsController(activity.window, view)
    }

    // 进入时隐藏系统栏，退出时恢复
    DisposableEffect(Unit) {
        windowInsetsController?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // showPanel 与系统栏同步：显示面板时显示系统栏，隐藏面板时隐藏系统栏
    LaunchedEffect(showPanel) {
        if (showPanel) {
            windowInsetsController?.show(WindowInsetsCompat.Type.systemBars())
        } else {
            windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // 屏幕常亮
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    // ── 加载章节
    LaunchedEffect(comicId, order) {
        viewModel.load(context, comicId, order)
    }

    // ── 滚动流：页 → 行 平铺（长图分割） ──────────────────────────────────
    val sliceCounts = remember { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(scrollMode) {
        if (!scrollMode) sliceCounts.clear()
    }
    LaunchedEffect(order) {
        sliceCounts.clear()
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

    // 切片前缀和：sliceCounts 变化时重建一次（O(n)），行→页查询降为 O(log n)
    // （原实现在每次滚动回调里从头累加，随页数线性变慢）
    val rowPrefix by remember(pages.size, scrollMode) {
        derivedStateOf {
            val arr = IntArray(pages.size + 1)
            for (p in pages.indices) {
                arr[p + 1] = arr[p] + (sliceCounts[p] ?: 1).coerceAtLeast(1)
            }
            arr
        }
    }

    fun rowToPage(row: Int): Int {
        if (pages.isEmpty()) return 0
        val prefix = rowPrefix
        val r = row.coerceAtLeast(0)
        // 二分查找第一个 prefix[p+1] > r 的页
        var lo = 0
        var hi = pages.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (prefix[mid + 1] > r) hi = mid else lo = mid + 1
        }
        return lo
    }

    fun rowForPage(page: Int): Int =
        rowPrefix[page.coerceIn(0, pages.size)]

    // 进度恢复：先按当前已知切片粗定位；随后等切片解析完成再校正（上限 3 秒）。
    // 原实现在切片未解析时按 1 片估算行号，长图章节会定位偏早若干屏。
    val restorePage = viewModel.pendingRestorePage
    LaunchedEffect(pages.size, restorePage, scrollMode) {
        if (pages.isNotEmpty() && restorePage >= 0) {
            viewModel.pendingRestorePage = -1
            val target = restorePage.coerceAtMost(pages.size - 1)
            if (!scrollMode) {
                pagerState.scrollToPage(target)
                return@LaunchedEffect
            }
            listState.scrollToItem(rowForPage(target))
            val deadline = System.currentTimeMillis() + 3_000
            var lastRow = -1
            while (System.currentTimeMillis() < deadline) {
                delay(200)
                val row = rowForPage(target)
                if (row != lastRow) {
                    // 用户已手动滚走（偏离上次自动定位超过 2 行）时不再拉回
                    val nearLast = lastRow == -1 ||
                        kotlin.math.abs(listState.firstVisibleItemIndex - lastRow) <= 2
                    if (nearLast) listState.scrollToItem(row)
                    lastRow = row
                }
                // 目标页之前的切片全部解析完成即可停止校正
                if ((0 until target).all { sliceCounts.containsKey(it) }) break
            }
        }
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

    // 保存进度：滚动页码变化做 1 秒防抖（连续快速滑动不再逐屏写盘、
    // 也不再高频全量重写"最近阅读"列表），切后台/退出立即保存兜底。
    //
    // 关键：状态读取必须发生在 snapshotFlow 的 lambda **内部**才有订阅效果。
    // 原实现把 currentPage（组合期算好的普通 val）放进去，块内没有任何 State 读取，
    // 于是 flow 只发射一次、本章内几乎不再保存进度。
    LaunchedEffect(pages.size, scrollMode) {
        snapshotFlow { if (scrollMode) scrollVisiblePage.value else pagerState.currentPage }
            .distinctUntilChanged()
            .debounce(1_000)
            .collect { page ->
                if (pages.isNotEmpty()) {
                    viewModel.saveProgress(page)
                    viewModel.recordRecentRead(page)
                }
            }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.saveProgress(currentPage)
        viewModel.recordRecentRead(currentPage)
    }
    // 退出阅读器兜底：单 Activity + Navigation 下点返回只触发 composable dispose，
    // 不会触发 Activity 的 ON_STOP，因此必须在这里补写一次，否则章内进度会丢。
    // rememberUpdatedState 保证读到的是 dispose 那一刻的最新页码，而非首次组合时的闭包值。
    val latestPage by rememberUpdatedState(currentPage)
    DisposableEffect(Unit) {
        onDispose {
            if (pages.isNotEmpty()) {
                viewModel.saveProgress(latestPage)
                viewModel.recordRecentRead(latestPage)
            }
        }
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

    // 翻页后复位缩放平移，避免上一页的偏移带到新页
    LaunchedEffect(pagerState.currentPage) {
        if (zoomScale <= 1f) {
            panX = 0f
            panY = 0f
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
                    // 唤出面板时系统栏会同步显示，避开系统导航条，避免压住亮度滑条和页码
                    modifier = Modifier.navigationBarsPadding(),
                    scrollMode = scrollMode,
                    onModeChange = { mode ->
                        if (mode != scrollMode) {
                            // 切换前先固定当前页码，切过去后定位到同一页
                            // （此前只做了「翻页→滚动」方向的同步，反向缺失：
                            //   读到第 50 页切横滑会瞬间跳回第 1 页，并可能写入错误进度）
                            val target = currentPage
                            scrollMode = mode
                            ReaderPrefs.current().readerMode = if (mode) 0 else 1
                            scope.launch {
                                if (mode) {
                                    listState.scrollToItem(rowToPage(target))
                                } else {
                                    pagerState.scrollToPage(
                                        target.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                                    )
                                }
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
                                if (zoomScale > 1f) {
                                    zoomScale = 1f
                                    panX = 0f
                                    panY = 0f
                                } else {
                                    zoomScale = 2f
                                }
                            }
                        },
                    )
                }
                // 缩放态下的捏合缩放与拖拽平移（仅翻页模式；滚动流交给 LazyColumn 处理）
                .pointerInput(scrollMode) {
                    if (scrollMode) return@pointerInput
                    detectTransformGestures { _, pan, zoom, _ ->
                        zoomScale = (zoomScale * zoom).coerceIn(1f, 4f)
                        val maxX = (zoomScale - 1f) * size.width / 2f
                        val maxY = (zoomScale - 1f) * size.height / 2f
                        panX = (panX + pan.x).coerceIn(-maxX, maxX)
                        panY = (panY + pan.y).coerceIn(-maxY, maxY)
                        if (zoomScale <= 1f) {
                            panX = 0f
                            panY = 0f
                        }
                    }
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
                        // 放大后关闭翻页滑动，把拖拽交给上方的平移手势，否则边缘内容看不全
                        userScrollEnabled = zoomScale <= 1f,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = panX
                                translationY = panY
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
    modifier: Modifier = Modifier,
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
    Surface(
        modifier = modifier,
        color = Color(0xE6000000),
    ) {
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
