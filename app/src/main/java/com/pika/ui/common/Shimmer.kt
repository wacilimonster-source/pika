package com.pika.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp

/**
 * 微光扫过动画（A2 骨架屏用）：线性渐变沿 x 轴循环平移。
 * 颜色取主题 surface 系，深浅色主题下均自然。
 */
fun Modifier.shimmerBrush(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmerProgress",
    )
    val base = MaterialTheme.colorScheme.surfaceVariant
    val highlight = MaterialTheme.colorScheme.surface
    val width = 600f
    background(
        Brush.linearGradient(
            colors = listOf(base, highlight.copy(alpha = 0.65f), base),
            start = Offset(progress * width - width / 3, 0f),
            end = Offset(progress * width + width / 3, 0f),
        ),
    )
}

/**
 * 漫画网格骨架屏（A2）：首屏加载时以卡片轮廓 + 微光替代「加载中...」文字，
 * 等待感知更短、弱网不再大片空白。骨架条数固定（约两屏），不可交互。
 */
@Composable
fun ComicGridSkeleton(
    columns: Int = 2,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(columns) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(3f / 4f)
                                .clip(RoundedCornerShape(8.dp))
                                .shimmerBrush(),
                        )
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth(0.85f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmerBrush(),
                        )
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(11.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmerBrush(),
                        )
                    }
                }
            }
        }
    }
}
