package com.pika.ui.category

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.pika.core.model.ComicCategory
import kotlin.math.roundToInt

@Composable
fun CategoryReorderDialog(
    categories: List<ComicCategory>,
    currentSettings: com.pika.data.CategorySettings.Settings,
    onSettingsChange: (com.pika.data.CategorySettings.Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    val items = remember(categories, currentSettings) {
        val orderMap = currentSettings.order.withIndex().associate { (i, id) -> id to i }
        categories.sortedBy { orderMap[it.id] ?: Int.MAX_VALUE }
    }
    var order by remember { mutableStateOf(items.map { it.id to it.title }) }
    var hidden by remember { mutableStateOf(currentSettings.hidden) }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var overIndex by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分类排序与显示") },
        text = {
            Column {
                Text(
                    text = "长按拖拽排序，点击眼睛切换显示/隐藏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    itemsIndexed(order, key = { _, item -> item.first }) { index, (catId, catTitle) ->
                        val isHidden = catId in hidden
                        val isDragged = draggedIndex == index
                        val isOver = overIndex == index && draggedIndex != null && draggedIndex != index

                        val bgColor by animateColorAsState(
                            targetValue = when {
                                isDragged -> MaterialTheme.colorScheme.primaryContainer
                                isOver -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                isHidden -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                            },
                            label = "bg",
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .graphicsLayer {
                                    if (isDragged) {
                                        scaleX = 1.03f
                                        scaleY = 1.03f
                                        shadowElevation = 8f
                                    }
                                }
                                .background(bgColor)
                                .pointerInput(index) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { draggedIndex = index },
                                        onDragEnd = {
                                            val from = draggedIndex
                                            val to = overIndex
                                            if (from != null && to != null && from != to) {
                                                val mutable = order.toMutableList()
                                                val item = mutable.removeAt(from)
                                                mutable.add(to, item)
                                                order = mutable
                                            }
                                            draggedIndex = null
                                            overIndex = null
                                        },
                                        onDragCancel = {
                                            draggedIndex = null
                                            overIndex = null
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            val itemHeight = 48.dp.toPx()
                                            val delta = (dragAmount.y / itemHeight).roundToInt()
                                            if (delta != 0) {
                                                val current = draggedIndex ?: index
                                                val target = (current + delta).coerceIn(0, order.size - 1)
                                                if (target != current) {
                                                    overIndex = target
                                                }
                                            }
                                        },
                                    )
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.DragHandle,
                                contentDescription = "拖拽",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = catTitle,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                color = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Icon(
                                imageVector = if (isHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (isHidden) "隐藏" else "显示",
                                tint = if (isHidden) MaterialTheme.colorScheme.error.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        hidden = if (catId in hidden) hidden - catId else hidden + catId
                                    },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSettingsChange(
                    com.pika.data.CategorySettings.Settings(
                        order = order.map { it.first },
                        hidden = hidden,
                    )
                )
                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}
