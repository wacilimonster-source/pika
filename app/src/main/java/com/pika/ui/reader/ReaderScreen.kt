package com.pika.ui.reader

import androidx.compose.animation.AnimatedVisibility
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import com.pika.core.download.DownloadManager
import com.pika.data.ReaderPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    ref: String,
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
    // 当前章节号以 VM 为准：切章只改 VM，导航路由参数不变，
    // 上下话按钮/标题按路由算会错乱（第二次"下一话"失效、上一话跳错章）
    val vmOrder by viewModel.currentOrderFlow.collectAsState()

    var scrollMode by remember { mutableStateOf(ReaderPrefs.current().readerMode == 0) }
    var showPanel by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(ReaderPrefs.current().brightness) }
    var zoomScale by remember { mutableFloatStateOf(1f) }
    // 缩放平移偏移：双击放大后允许拖动查看画面边缘（否则放大后只能看正中央）
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    // 双击缩放的过渡动画 Job：捏合手势开始时会取消它，避免动画与手势互相抢写
    var zoomAnimJob by remember { mutableStateOf<Job?>(null) }
    // 内容区像素尺寸（缩放平移的钳制范围用）
    var contentSize by remember { mutableStateOf(IntSize.Zero) }

    // 音量键翻页（设置页开关，默认关）
    val volumeKeyPaging by ReaderPrefs.current().volumeKeyPaging.collectAsState(initial = false)
    val focusRequester = remember { FocusRequester() }

    // 续读轻提示：进度恢复成功且页码 > 0 时短暂展示，并提供「从头看」出口
    val snackbarHostState = remember { SnackbarHostState() }
    var resumeFromPage by remember { mutableIntStateOf(-1) }

    // 章节列表抽屉
    var showChapterSheet by remember { mutableStateOf(false) }
    var readChapters by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val chapterDesc by ReaderPrefs.current().chapterListDescending.collectAsState(initial = false)

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
    LaunchedEffect(ref, order) {
        viewModel.load(context, ref, order)
    }

    // ── 滚动流：页 → 行 平铺（长图分割） ──────────────────────────────────
    val sliceCounts = remember { mutableStateMapOf<Int, Int>() }
    LaunchedEffect(scrollMode) {
        if (!scrollMode) sliceCounts.clear()
    }
    // 切章必须以 VM 的当前章节为准清空并预置切片数缓存：
    // 路由 order 在切章后不变，按它做 key 会让上一章的切片数污染新章的行换算；
    // 预置历史缓存后恢复定位前就有真实"页→行"换算，长图章节不再系统性偏早
    LaunchedEffect(vmOrder, pages.size) {
        sliceCounts.clear()
        if (pages.isNotEmpty()) {
            val cached = com.pika.data.WebtoonSliceCache.load(ref, vmOrder, context)
            if (cached.isNotEmpty()) sliceCounts.putAll(cached)
        }
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
    // restoring 标记期间挂起进度写盘：恢复中的偏早页码绝不能被防抖写成进度，
    // 否则"恢复偏差 → 写回 → 再偏差"正反馈会让条漫进度持续倒退。
    // 恢复页以 StateFlow 收集：effect 由状态变化确定性触发（普通 var 依赖"恰好重组"），
    // 触发时 pages 必已是本章——VM 保证先赋 pages、后发布恢复页
    val pendingRestore by viewModel.pendingRestorePage.collectAsState()
    val restoring = remember { mutableStateOf(false) }
    LaunchedEffect(pages.size, pendingRestore, scrollMode) {
        if (pages.isNotEmpty() && pendingRestore >= 0) {
            val target = pendingRestore.coerceAtMost(pages.size - 1)
            restoring.value = true
            try {
                if (!scrollMode) {
                    pagerState.scrollToPage(target)
                } else {
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
                // 恢复成功才提示（被切章取消时不会走到这里）；页码 0 无需提示
                if (target > 0) resumeFromPage = target
            } finally {
                restoring.value = false
                // 恢复完成后再消费：StateFlow 值相同会去重，消费本身不会反向重启
                // 本 effect 打断校正；被切章取消时 load() 已复位为 -1，此处写入是去重的 no-op
                viewModel.consumePendingRestore()
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
    // key 必须含 vmOrder：防抖链随切章重启——否则同页数的两章之间链幸存，
    // 旧章最后一次翻页会被 1 秒防抖带着新章号写盘（进度跨章泄漏）。
    LaunchedEffect(vmOrder, pages.size, scrollMode) {
        snapshotFlow { if (scrollMode) scrollVisiblePage.value else pagerState.currentPage }
            .distinctUntilChanged()
            .debounce(1_000)
            .collect { page ->
                // 恢复定位期间不写盘：此时显示的偏早页码不是用户真实意图
                if (pages.isNotEmpty() && !restoring.value) {
                    viewModel.saveProgress(page)
                    viewModel.recordRecentRead(page)
                }
            }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!restoring.value) {
            viewModel.saveProgress(currentPage)
            viewModel.recordRecentRead(currentPage)
        }
    }
    // 退出阅读器兜底：单 Activity + Navigation 下点返回只触发 composable dispose，
    // 不会触发 Activity 的 ON_STOP，因此必须在这里补写一次，否则章内进度会丢。
    // rememberUpdatedState 保证读到的是 dispose 那一刻的最新页码，而非首次组合时的闭包值。
    // final=true：写盘落到不随 VM 取消的作用域（返回键会立刻销毁 VM 作用域，
    // 在途的 DataStore 写入会被取消导致最后 1 秒内的翻页进度丢失）。
    val latestPage by rememberUpdatedState(currentPage)
    DisposableEffect(Unit) {
        onDispose {
            if (pages.isNotEmpty() && !restoring.value) {
                viewModel.saveProgress(latestPage, final = true)
                viewModel.recordRecentRead(latestPage, final = true)
            }
        }
    }

    // 预加载前后 2 页
    LaunchedEffect(currentPage) {
        if (pages.isNotEmpty()) viewModel.preloadNearby(context, currentPage, range = 2)
    }

    // ── 切章 ──────────────────────────────────────────────────────────────
    val sortedChapters = remember(chapters) { chapters.sortedBy { it.order } }
    val currentChapterIndex = sortedChapters.indexOfFirst { it.order == vmOrder }
    fun switchTo(targetOrder: Int) {
        showPanel = false
        showChapterSheet = false
        viewModel.switchChapter(context, targetOrder)
    }

    // 双击缩放（两种阅读模式均可），带 200ms 过渡动画
    fun animateZoomTo(target: Float) {
        zoomAnimJob?.cancel()
        zoomAnimJob = scope.launch {
            animate(
                initialValue = zoomScale,
                targetValue = target,
                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
            ) { value, _ -> zoomScale = value }
            if (target <= 1f) {
                panX = 0f
                panY = 0f
            }
        }
    }

    fun resetZoom() {
        zoomAnimJob?.cancel()
        zoomScale = 1f
        panX = 0f
        panY = 0f
    }

    // 进度条拖动跳页：滚动流按页码映射到行，并沿用续读恢复的切片校正逻辑
    // （校正期间挂起进度写盘，避免跳转途中的页码被防抖写成进度）
    fun seekToPage(page: Int) {
        if (pages.isEmpty()) return
        val target = page.coerceIn(0, pages.size - 1)
        resetZoom()
        scope.launch {
            if (!scrollMode) {
                pagerState.scrollToPage(target)
            } else {
                restoring.value = true
                try {
                    listState.scrollToItem(rowForPage(target))
                    val deadline = System.currentTimeMillis() + 3_000
                    var lastRow = -1
                    while (System.currentTimeMillis() < deadline) {
                        delay(150)
                        val row = rowForPage(target)
                        if (row != lastRow) {
                            val nearLast = lastRow == -1 ||
                                kotlin.math.abs(listState.firstVisibleItemIndex - lastRow) <= 2
                            if (nearLast) listState.scrollToItem(row)
                            lastRow = row
                        }
                        if ((0 until target).all { sliceCounts.containsKey(it) }) break
                    }
                } finally {
                    restoring.value = false
                }
            }
            viewModel.saveProgress(target)
            viewModel.recordRecentRead(target)
        }
    }

    // 续读轻提示：不弹窗不打断，短暂展示并提供「从头看」出口
    LaunchedEffect(resumeFromPage) {
        val from = resumeFromPage
        if (from > 0 && pages.isNotEmpty()) {
            val result = snackbarHostState.showSnackbar(
                message = "已回到上次进度 · 第 ${from + 1} 页",
                actionLabel = "从头看",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                seekToPage(0)
            }
            if (resumeFromPage == from) resumeFromPage = -1
        }
    }

    // 翻页/翻屏（点按区域、音量键共用）：zone -1 上 / +1 下
    fun pageTurn(zone: Int) {
        when {
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

    // 点按：左 30% 上翻 / 右 30% 下翻 / 中间 40% 面板
    fun onTap(offsetX: Float, width: Int) {
        val zone = when {
            offsetX < width * 0.3f -> -1
            offsetX > width * 0.7f -> 1
            else -> 0
        }
        when {
            // 放大状态下边缘点按不再翻页/翻屏（防误触），中间区域仍可唤出面板
            zoomScale > 1f && zone != 0 -> return
            zone == 0 -> showPanel = !showPanel
            else -> pageTurn(zone)
        }
    }

    // 长按保存当前页到相册（Pictures/PiKA）
    fun saveCurrentPage() {
        // 只读状态委托：pointerInput(Unit) 的闭包是首次组合时创建的，读普通 val 会拿到旧值
        val page = if (scrollMode) {
            scrollVisiblePage.value.coerceAtMost((pages.size - 1).coerceAtLeast(0))
        } else {
            pagerState.currentPage
        }
        val url = viewModel.pageUrlAt(page) ?: return
        scope.launch {
            val result = runCatching {
                com.pika.util.ImageSaver.saveToGallery(
                    context,
                    url,
                    title.ifBlank { epTitle }.ifBlank { "第 $vmOrder 话" },
                    page + 1,
                )
            }
            Toast.makeText(
                context,
                if (result.isSuccess) "已保存到相册" else "保存失败：${result.exceptionOrNull()?.message ?: "未知错误"}",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // 翻页后复位缩放平移，避免上一页的偏移带到新页
    LaunchedEffect(pagerState.currentPage) {
        if (zoomScale <= 1f) {
            panX = 0f
            panY = 0f
        }
    }

    // 切章复位缩放（跨章保留缩放没有意义，且滚动流行高已变）
    LaunchedEffect(vmOrder) {
        resetZoom()
    }

    // 章节抽屉打开时加载章节已读标记（打勾用）
    LaunchedEffect(showChapterSheet, vmOrder) {
        if (showChapterSheet) {
            readChapters = runCatching { ReaderPrefs.current().readChaptersAsync(ref) }
                .getOrDefault(emptySet())
        }
    }

    // 面板/抽屉收起后收回焦点，保证音量键翻页持续可用
    LaunchedEffect(showPanel, showChapterSheet) {
        if (!showPanel && !showChapterSheet) {
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(visible = showPanel) {
                TopAppBar(
                    title = {
                        Text(
                            text = title.ifBlank { epTitle }.ifBlank { "第 $vmOrder 话" },
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
                            resetZoom()
                            scrollMode = mode
                            ReaderPrefs.current().readerMode = if (mode) 0 else 1
                            scope.launch {
                                if (mode) {
                                    // 页码 → 行号必须用 rowForPage：rowToPage 方向相反，
                                    // 用反会把页码当行号，长图分屏章节切滚动流会跳回前面几十页
                                    listState.scrollToItem(rowForPage(target))
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
                    pageIndex = currentPage,
                    totalPages = pages.size,
                    hasPrev = currentChapterIndex > 0,
                    hasNext = currentChapterIndex in 0 until sortedChapters.size - 1,
                    onPrevChapter = {
                        sortedChapters.getOrNull(currentChapterIndex - 1)?.let { switchTo(it.order) }
                    },
                    onNextChapter = {
                        sortedChapters.getOrNull(currentChapterIndex + 1)?.let { switchTo(it.order) }
                    },
                    onOpenChapters = { showChapterSheet = true },
                    onSeek = { seekToPage(it) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .onSizeChanged { contentSize = it }
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    // 音量键翻页：按下/抬起都拦截（防止系统调音量），抬起时翻一页
                    if (!volumeKeyPaging) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.VolumeUp, Key.VolumeDown -> {
                            if (event.type == KeyEventType.KeyUp) {
                                pageTurn(if (event.key == Key.VolumeUp) -1 else 1)
                            }
                            true
                        }
                        else -> false
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { offset -> onTap(offset.x, size.width) },
                        onDoubleTap = {
                            if (pages.isNotEmpty()) {
                                if (zoomScale > 1f) animateZoomTo(1f) else animateZoomTo(2f)
                            }
                        },
                        onLongPress = { saveCurrentPage() },
                    )
                }
                // 双指捏合缩放 + 放大态平移（两种阅读模式均可，见 readerZoomGesture 注释）
                .readerZoomGesture(
                    isZoomed = { zoomScale > 1f },
                    onGesture = { pan, zoom ->
                        zoomAnimJob?.cancel()
                        val newScale = (zoomScale * zoom).coerceIn(1f, 3f)
                        zoomScale = newScale
                        val maxX = (newScale - 1f) * contentSize.width / 2f
                        val maxY = (newScale - 1f) * contentSize.height / 2f
                        panX = (panX + pan.x).coerceIn(-maxX, maxX)
                        panY = (panY + pan.y).coerceIn(-maxY, maxY)
                        if (newScale <= 1f) {
                            panX = 0f
                            panY = 0f
                        }
                    },
                ),
        ) {
            when {
                pages.isEmpty() && loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                pages.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "加载失败",
                                color = Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(16.dp))
                            Row {
                                OutlinedButton(onClick = { viewModel.retry(context) }) {
                                    Text("重试", color = Color.White)
                                }
                                Spacer(Modifier.width(12.dp))
                                OutlinedButton(onClick = onBack) {
                                    Text("返回", color = Color.White)
                                }
                            }
                        }
                    }
                }

                scrollMode -> {
                    LazyColumn(
                        state = listState,
                        // 放大后关闭列表滚动，把拖拽交给上方的平移手势
                        userScrollEnabled = zoomScale <= 1f,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoomScale
                                scaleY = zoomScale
                                translationX = panX
                                translationY = panY
                            },
                    ) {
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
                                onSliceCountResolved = { p, n ->
                                    sliceCounts[p] = n
                                    // 写入持久缓存：下次进入本章恢复定位可直接按真实行号换算
                                    if (n > 1) {
                                        com.pika.data.WebtoonSliceCache.putAll(
                                            context, ref, vmOrder, mapOf(p to n),
                                        )
                                    }
                                },
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
                        PagerPage(
                            pageIndex = page,
                            imageUrl = pages[page].imageUrl,
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

    // ── 章节列表抽屉 ──────────────────────────────────────────────────────────
    if (showChapterSheet) {
        val sheetChapters = if (chapterDesc) sortedChapters.asReversed() else sortedChapters
        ModalBottomSheet(
            onDismissRequest = { showChapterSheet = false },
            containerColor = Color(0xFF161616),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "章节（${sortedChapters.size}）",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                ReaderPrefs.current().setChapterListDescending(!chapterDesc)
                            }
                        },
                    ) {
                        Text(
                            text = if (chapterDesc) "倒序 ↓" else "正序 ↑",
                            color = Color.White,
                        )
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp),
                ) {
                    items(sheetChapters, key = { it.order }) { chapter ->
                        val isCurrent = chapter.order == vmOrder
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showChapterSheet = false
                                    switchTo(chapter.order)
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = chapter.title.ifBlank { "第 ${chapter.order} 话" },
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isCurrent) FontWeight.Bold else null,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (chapter.order in readChapters) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "已读",
                                    tint = Color(0xFF8A8A8A),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            if (DownloadManager.isDownloaded(viewModel.comicId, chapter.order)) {
                                Text(
                                    text = "已下载",
                                    color = Color(0xFF8A8A8A),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 阅读器底部控制面板：亮度 / 进度条拖动跳页 / 模式切换 / 章节列表入口 / 上一话·下一话·页码
 */
@Composable
private fun ReaderControlPanel(
    modifier: Modifier = Modifier,
    scrollMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    brightness: Float,
    onBrightnessChange: (Float) -> Unit,
    /** 当前页码（0 基） */
    pageIndex: Int,
    totalPages: Int,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenChapters: () -> Unit,
    /** 拖动进度条跳页（参数为目标页码，0 基） */
    onSeek: (Int) -> Unit,
) {
    // 拖动中的临时值：拖动时页码标签实时跟随，松手才真正跳页
    var draggingPage by remember { mutableStateOf(false) }
    var dragPageValue by remember { mutableFloatStateOf(0f) }

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
            // 进度条：拖动跳页（页数未知或单页时隐藏）
            if (totalPages > 1) {
                Slider(
                    value = if (draggingPage) dragPageValue else pageIndex.toFloat().coerceIn(0f, (totalPages - 1).toFloat()),
                    onValueChange = {
                        draggingPage = true
                        dragPageValue = it
                    },
                    valueRange = 0f..(totalPages - 1).toFloat(),
                    onValueChangeFinished = {
                        draggingPage = false
                        onSeek(dragPageValue.roundToInt())
                    },
                    modifier = Modifier.fillMaxWidth(),
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
                IconButton(onClick = onOpenChapters) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = "章节列表",
                        tint = Color.White,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevChapter, enabled = hasPrev) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一话",
                        tint = if (hasPrev) Color.White else Color.Gray,
                    )
                }
                Text(
                    text = if (draggingPage) {
                        "${dragPageValue.roundToInt() + 1} / $totalPages"
                    } else {
                        "${pageIndex + 1} / $totalPages"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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

/** 横滑翻页模式的单页渲染：加载转圈占位、失败可点击重试（与滚动流体验对齐） */
@Composable
private fun PagerPage(
    pageIndex: Int,
    imageUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val decodeHeightCap = remember(context) { maxDecodeHeightPx(context) }
    // 失败重试：改变 model（追加 fragment）强制 Coil 重新请求（与 WebtoonSplitPage 同一手法）
    var retryTick by remember(pageIndex) { mutableIntStateOf(0) }
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(if (retryTick == 0) imageUrl else "$imageUrl#retry$retryTick")
            .size(coil.size.Size(width = Int.MAX_VALUE, height = decodeHeightCap))
            .build(),
    )
    val state = painter.state

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (state) {
            is AsyncImagePainter.State.Loading -> {
                CircularProgressIndicator(color = Color.White)
            }

            is AsyncImagePainter.State.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "第 ${pageIndex + 1} 页加载失败",
                        color = Color.Gray,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "点击重试",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { retryTick++ },
                    )
                }
            }

            else -> {
                Image(
                    painter = painter,
                    contentDescription = "第 ${pageIndex + 1} 页",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 阅读器缩放/平移手势（Initial pass，先于 LazyColumn/Pager 子级处理）：
 * - 双指捏合：无歧义手势，立即接管并消费事件（子级滚动/翻页取消）；
 * - 已放大的单指拖动：接管为平移（此时列表滚动/翻页均已禁用）；
 * - 未放大的单指：不拦截，保持 LazyColumn 滚动 / Pager 翻页 / 点按分区原行为。
 */
private fun Modifier.readerZoomGesture(
    isZoomed: () -> Boolean,
    onGesture: (pan: Offset, zoom: Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var intercepting = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) break
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount >= 2) intercepting = true
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            val hasMotion = zoom != 1f || pan != Offset.Zero
            if (!intercepting && !isZoomed()) continue
            if (pressedCount >= 2 || hasMotion) {
                intercepting = true
                event.changes.forEach { it.consume() }
                if (hasMotion) onGesture(pan, zoom)
            }
        }
    }
}
