package com.pika.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 页码分页条（紧凑）：‹ 1 2 3 … N › 页码 [跳页]
 * 当前页高亮，点击跳页，左右箭头翻页，右侧输入框可直接输入页码跳转。
 */
@Composable
fun PaginationBar(
    currentPage: Int,
    totalPages: Int,
    onPageChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (totalPages <= 1) return
    var jumpInput by remember { mutableStateOf("") }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onPageChange((currentPage - 1).coerceAtLeast(1)) },
            enabled = currentPage > 1,
            modifier = Modifier.width(32.dp).height(32.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "上一页",
                modifier = Modifier.width(18.dp).height(18.dp),
            )
        }
        pageWindow(currentPage, totalPages).forEach { p ->
            if (p == null) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
            } else {
                Surface(
                    onClick = { onPageChange(p) },
                    shape = MaterialTheme.shapes.small,
                    color = if (p == currentPage) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp),
                ) {
                    Text(
                        text = "$p",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (p == currentPage) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
        IconButton(
            onClick = { onPageChange((currentPage + 1).coerceAtMost(totalPages)) },
            enabled = currentPage < totalPages,
            modifier = Modifier.width(32.dp).height(32.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "下一页",
                modifier = Modifier.width(18.dp).height(18.dp),
            )
        }
        TextField(
            value = jumpInput,
            onValueChange = { jumpInput = it.filter { c -> c.isDigit() }.take(4) },
            singleLine = true,
            placeholder = { Text("$currentPage/$totalPages", fontSize = 10.sp) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                val p = jumpInput.toIntOrNull()
                if (p != null && p in 1..totalPages) {
                    onPageChange(p)
                    jumpInput = ""
                }
            }),
            textStyle = MaterialTheme.typography.labelSmall,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                disabledIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            modifier = Modifier
                .width(52.dp)
                .height(30.dp)
                .padding(start = 4.dp),
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
