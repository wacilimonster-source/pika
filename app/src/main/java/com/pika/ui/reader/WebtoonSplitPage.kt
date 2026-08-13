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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
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

    // 图片"高/宽"比。默认取「切片数 × 视口比」，加载完成前的占位高度恰好为一屏，避免跳动
    var heightRatio by remember(pageIndex, safeSliceCount) {
        mutableFloatStateOf(safeSliceCount * viewportAspect)
    }

    // 失败重试：改变 model（追加 fragment）强制 Coil 重新请求（HTTP 请求不受 fragment 影响）
    var retryTick by remember(pageIndex) { mutableIntStateOf(0) }
    val painter = rememberAsyncImagePainter(
        model = if (retryTick == 0) imageUrl else "$imageUrl#retry$retryTick",
    )
    // Coil 2.7 的 painter.state 是快照属性（getter 读内部 mutableStateOf），读取即订阅重组
    val state = painter.state

    // 图片加载成功后用真实尺寸刷新宽高比，并上报切片数（仅首屏）
    val intrinsicSize = (state as? AsyncImagePainter.State.Success)?.painter?.intrinsicSize
    LaunchedEffect(intrinsicSize) {
        if (intrinsicSize != null && intrinsicSize.width > 0f && intrinsicSize.height > 0f) {
            val ratio = intrinsicSize.height / intrinsicSize.width
            if (heightRatio != ratio) heightRatio = ratio
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

/** 计算单页应切分为多少屏（超出一屏 1.4 倍才切）。 */
internal fun computeSliceCount(imageHeightRatio: Float, viewportAspect: Float): Int {
    if (imageHeightRatio <= 0f || viewportAspect <= 0f) return 1
    val raw = imageHeightRatio / viewportAspect
    return if (raw <= 1.4f) 1 else ceil(raw).toInt().coerceAtLeast(1)
}
