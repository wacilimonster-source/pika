package com.pika.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 漫画网格的列数自适应间距（设计稿：两列封面约占屏宽 41%，三列约 30%）。
 *
 * 真实网格与骨架屏共用同一份数值：此前两处各写一遍，调整列间距时骨架会与
 * 加载完成后的真实卡片错位，产生明显跳变。
 */
internal data class ComicGridMetrics(
    val horizontalPadding: Dp,
    val columnGap: Dp,
    val rowGap: Dp,
)

internal fun comicGridMetrics(columns: Int): ComicGridMetrics =
    if (columns == 2) {
        ComicGridMetrics(horizontalPadding = 24.dp, columnGap = 20.dp, rowGap = 20.dp)
    } else {
        ComicGridMetrics(horizontalPadding = 12.dp, columnGap = 8.dp, rowGap = 16.dp)
    }
