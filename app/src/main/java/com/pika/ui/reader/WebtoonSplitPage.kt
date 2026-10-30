package com.pika.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 滚动流（条漫）单页渲染。
 *
 * 支持「条漫长图自动分割」：超过阈值的长图被切分为多个屏高切片，每个切片作为
 * LazyColumn 的一个 item，逐屏平铺，贴合竖屏、避免单张超长图无尽滚动。
 *
 * - [isPrimary]（sliceIndex==0）负责在图片加载完成后把切片数上报给外层
 *   （onSliceCountResolved），外层据此把该页展开成多个 item。
 * - 未超阈值时整图显示。
 */
@Composable
fun WebtoonSplitPage(
    pageIndex: Int,
    imageUrl: String,
    sliceIndex: Int,
    sliceCount: Int,
    viewportAspect: Float,
    splitEnabled: Boolean,
    isPrimary: Boolean,
    onSliceCountResolved: (pageIndex: Int, count: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val safeSliceCount = sliceCount.coerceAtLeast(1)

    // 图片"高/宽"比。
    // 测量到真实比例前，用「切片数 × 视口比」占位，高度恰好为一屏，避免跳动。
    // 关键：真实比例一旦测到就以它为准，**不能再被占位值覆盖**。
    // 原实现把 heightRatio 的 remember key 设为 (pageIndex, safeSliceCount)，
    // 切片数从 1 变为 n 时会把已测得的真实比例重置成 n×viewportAspect 的估算值，
    // 而 LaunchedEffect(intrinsicSize) 因尺寸未变不会重跑，于是分屏高度/偏移按估算值计算。
    var measuredRatio by remember(pageIndex) { mutableStateOf<Float?>(null) }
    val heightRatio: Float = measuredRatio ?: (safeSliceCount * viewportAspect)

    // 失败重试：改变 model（追加 fragment）强制 Coil 重新请求（HTTP 请求不受 fragment 影响）
    var retryTick by remember(pageIndex) { mutableIntStateOf(0) }
    // 解码像素上限：宽高都封顶，防超大图整图解码后「Canvas: trying to draw too
    // large bitmap」直接闪退（P1，v1.9.13 回归实测：13830×8192 的超宽长图 = 108MB，
    // 超过 Android Canvas 100MB 上限）。此前只限制高度，宽度传 Int.MAX_VALUE
    // 表示不约束——超宽图（跨页彩图/长条扫描）会解码成巨图。
    // 宽度上限 2160：典型手机屏宽 ~1080，留 2 倍余量；与高度上限组合的最坏位图
    // 2160×8192×4 ≈ 70MB（<100MB Canvas 上限），低内存档更小。
    // 上限按设备内存等级自适应（lowMemory 设备高度降到 4k，减轻位图压力）
    val context = LocalContext.current
    val decodeHeightCap = remember(context) { maxDecodeHeightPx(context) }
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(LocalContext.current)
            .data(if (retryTick == 0) imageUrl else "$imageUrl#retry$retryTick")
            // model 追加 fragment 会把默认缓存键一并漂移：显式固定为裸 URL，
            // 重试成功的结果能被其他切片/预取/回滚重建的 item 直接复用
            // （否则存进 #retryN 键下，回滚后请求裸 URL 全量重新下载）
            .memoryCacheKey(imageUrl)
            .diskCacheKey(imageUrl)
            .size(coil.size.Size(
                width = MAX_DECODE_WIDTH_PX,
                height = decodeHeightCap,
            ))
            .build(),
    )
    // Coil 2.7 的 painter.state 是快照属性（getter 读内部 mutableStateOf），读取即订阅重组
    val state = painter.state

    // 渲染层失败留痕：图片管线的失败由 ErrorLoggingInterceptor 记录；本层再记一条
    // 「画家态」失败（页号+URL），用于分辨「管线零失败但页面显示失败」的盲区
    LaunchedEffect(state) {
        if (state is AsyncImagePainter.State.Error) {
            val t = state.result.throwable
            val msg = "渲染层失败(第${pageIndex + 1}页): ${t.javaClass.simpleName}: ${t.message} <- $imageUrl"
            android.util.Log.e("PainterErr", msg)
            com.pika.core.log.LogStore.log("PainterErr", "E", msg)
        }
    }

    // 图片加载成功后用真实尺寸刷新宽高比，并上报切片数（仅首屏）
    val intrinsicSize = (state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize
    LaunchedEffect(intrinsicSize) {
        if (intrinsicSize != null && intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
            val ratio = intrinsicSize.height / intrinsicSize.width
            // 写入 measuredRatio（而非可被重置的 heightRatio）
            measuredRatio = ratio
            if (isPrimary && splitEnabled) {
                val n = computeSliceCount(ratio, viewportAspect)
                onSliceCountResolved(pageIndex, n)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val totalHeightPx = (widthPx * heightRatio).coerceAtLeast(1f)
        val sliceHeightPx = (totalHeightPx / safeSliceCount).coerceAtLeast(1f)
        val sliceHeightDp = with(density) { sliceHeightPx.toDp() }
        val totalHeightDp = with(density) { totalHeightPx.toDp() }

        if (!splitEnabled || safeSliceCount <= 1) {
            // 整图显示（普通页 / 未开启分割）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(with(density) { (widthPx * heightRatio).toDp() })
                    .clipToBounds()
                    .background(Color.Gray.copy(alpha = 0.1f)),
            ) {
                PageImage(
                    painter = painter,
                    state = state,
                    pageIndex = pageIndex,
                    contentScale = ContentScale.FillWidth,
                    fullHeightDp = with(density) { (widthPx * heightRatio).toDp() },
                    onRetry = { retryTick++ },
                )
            }
        } else {
            // 长图切分：本 item 只显示第 sliceIndex 屏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sliceHeightDp)
                    .clipToBounds()
                    .background(Color.Gray.copy(alpha = 0.1f)),
            ) {
                PageImage(
                    painter = painter,
                    state = state,
                    pageIndex = pageIndex,
                    contentScale = ContentScale.FillWidth,
                    fullHeightDp = totalHeightDp,
                    offsetY = -(sliceIndex * sliceHeightPx).roundToInt(),
                    onRetry = { retryTick++ },
                )
            }
        }
    }
}

/** 单切片内容：成功画图，加载中占位，失败可点击重试。 */
@Composable
private fun PageImage(
    painter: AsyncImagePainter,
    state: AsyncImagePainter.State,
    pageIndex: Int,
    contentScale: ContentScale,
    fullHeightDp: androidx.compose.ui.unit.Dp,
    onRetry: () -> Unit,
    offsetY: Int = 0,
) {
    when (state) {
        is AsyncImagePainter.State.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }

        is AsyncImagePainter.State.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "第 ${pageIndex + 1} 页加载失败\n点击重试",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                )
            }
        }

        else -> {
            Image(
                painter = painter,
                contentDescription = "第 ${pageIndex + 1} 页",
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxWidth()
                    // requiredHeight 忽略父级约束，保持完整图片高度再向上位移
                    .requiredHeight(fullHeightDp)
                    .offset { IntOffset(0, offsetY) },
            )
        }
    }
}

/** 计算单页应切分为多少屏（超出一屏 1.4 倍才切；上限 6 屏防位图过大）。 */
internal fun computeSliceCount(imageHeightRatio: Float, viewportAspect: Float): Int {
    if (imageHeightRatio <= 0f || viewportAspect <= 0f) return 1
    val raw = imageHeightRatio / viewportAspect
    return if (raw <= 1.4f) 1 else ceil(raw).toInt().coerceIn(1, 6)
}

/** 单张位图解码高度上限（px）：1080 宽 × 8192 高 ≈ 35MB */
private const val MAX_DECODE_HEIGHT_PX = 8192

/** 单张位图解码宽度上限（px）：超宽图（跨页彩图/长条扫描）必须同样封顶，
 *  否则 13830×8192 这类图解码成 108MB 位图，绘制时触发
 *  「Canvas: trying to draw too large bitmap」闪退（Android Canvas 上限 100MB）。
 *  2160 覆盖 1080 屏宽的 2 倍余量，与高度上限组合的最坏位图约 70MB。 */
internal const val MAX_DECODE_WIDTH_PX = 2160

/**
 * 按设备内存等级自适应解码上限：
 * - 大内存设备（memoryClass ≥ 256MB）维持 8192，长图切片清晰度最优；
 * - 小内存设备降到 4096（约 17MB/张），显著降低缓存驱逐导致的重复解码与 GC 抖动。
 *
 * internal：ReaderScreen 的 PagerPage（横滑翻页模式）与预加载共用同一上限，
 * 保证预取缓存键与展示请求解码尺寸一致。
 */
internal fun maxDecodeHeightPx(context: android.content.Context): Int {
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
        as? android.app.ActivityManager
    val memClass = am?.memoryClass ?: 192
    return when {
        memClass >= 256 -> MAX_DECODE_HEIGHT_PX
        memClass >= 128 -> 6144
        else -> 4096
    }
}
