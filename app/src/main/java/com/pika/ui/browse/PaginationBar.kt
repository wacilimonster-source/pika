package com.pika.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 页码分页条：‹ 1 2 3 … N ›
 * 当前页高亮，点击跳页，左右箭头翻页；页数多时自动折叠显示省略号。
 */
@Composable
fun PaginationBar(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalPages <= 1) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onPageChange((currentPage - 1).coerceAtLeast(1)) },
            enabled = currentPage > 1,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一页")
        }
        pageWindow(currentPage, totalPages).forEach { p ->
            if (p == null) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                FilterChip(
                    selected = p == currentPage,
                    onClick = { onPageChange(p) },
                    label = { Text("$p") },
                    modifier = Modifier.padding(horizontal = 1.dp),
                )
            }
        }
        IconButton(
            onClick = { onPageChange((currentPage + 1).coerceAtMost(totalPages)) },
            enabled = currentPage < totalPages,
        ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一页")
        }
        Text(
            text = "$currentPage/$totalPages",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/** 生成页码窗口：当前页前后各 1 页 + 首尾页，间隔>1 处插入省略号(null) */
private fun pageWindow(current: Int, total: Int): List<Int?> {
    if (total <= 7) return (1..total).toList()
    val pages = listOf(1, current - 1, current, current + 1, total)
        .filter { it in 1..total }
        .distinct()
        .sorted()
    val result = mutableListOf<Int?>()
    var prev = 0
    for (p in pages) {
        if (p - prev > 1) result.add(null)
        result.add(p)
        prev = p
    }
    return result
}
