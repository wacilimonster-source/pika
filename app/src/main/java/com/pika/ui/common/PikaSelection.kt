package com.pika.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * PiKA 统一选中态组件。
 *
 * 设计约定（深浅色主题一致）：
 * - 选中：主色实心 + onPrimary 文字（不使用 Material 默认的对勾图标）
 * - 未选中：surfaceContainerLow 底色 + 1dp outlineVariant 描边 + onSurfaceVariant 文字
 * - 状态切换 180ms 颜色过渡，与 design-tokens motion.stateTransition 一致
 */

/**
 * 单行分段控件（日/周/月榜、设置项 2/3 列等）。
 *
 * 选中项为主色实心胶囊，靠底色和字重区分，不带对勾图标。
 *
 * @param items 选项列表
 * @param selected 当前选中项
 * @param onSelect 选中回调
 * @param modifier 修饰符
 * @param equalWidth 是否等分宽度（需要铺满父容器时开启）
 * @param label 选项文案
 */
@Composable
fun <T> PikaSegmentedControl(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    equalWidth: Boolean = false,
    label: (T) -> String,
) {
    val colors = MaterialTheme.colorScheme
    val groupShape = RoundedCornerShape(12.dp)
    val itemShape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .selectableGroup()
            .background(colors.surfaceContainerLow, groupShape)
            .border(1.dp, colors.outline, groupShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val active = item == selected
            val background by animateColorAsState(
                targetValue = if (active) colors.primary else Color.Transparent,
                animationSpec = tween(180),
                label = "segmentBackground",
            )
            val foreground by animateColorAsState(
                targetValue = if (active) colors.onPrimary else colors.onSurfaceVariant,
                animationSpec = tween(180),
                label = "segmentForeground",
            )
            val widthModifier = if (equalWidth) Modifier.weight(1f) else Modifier
            Box(
                modifier = widthModifier
                    .heightIn(min = 40.dp)
                    .clip(itemShape)
                    .background(background, itemShape)
                    .selectable(
                        selected = active,
                        onClick = { onSelect(item) },
                        role = Role.RadioButton,
                    )
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(item),
                    color = foreground,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 筛选 chip（排序 / 阅读状态 / 标签等单选项）。
 *
 * 与分段控件共用同一套选中语言：选中为主色实心，未选中为浅底 + 描边。
 * 保留 Material FilterChip 的水波纹、最小触控区域与 selected 无障碍语义。
 */
@Composable
fun PikaFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    PikaFilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        label = { Text(text = text) },
    )
}

/** 自定义 label 版本（如作者片段 chip 内嵌角色标签） */
@Composable
fun PikaFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = label,
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = colors.surfaceContainerLow,
            labelColor = colors.onSurfaceVariant,
            selectedContainerColor = colors.primary,
            selectedLabelColor = colors.onPrimary,
            disabledContainerColor = colors.surfaceContainerLow.copy(alpha = 0.5f),
            disabledLabelColor = colors.onSurfaceVariant.copy(alpha = 0.38f),
        ),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                !enabled -> colors.outline.copy(alpha = 0.5f)
                selected -> colors.primary
                else -> colors.outline
            },
        ),
        modifier = modifier,
    )
}
