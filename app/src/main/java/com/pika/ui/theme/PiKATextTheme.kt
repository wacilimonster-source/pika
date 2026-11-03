package com.pika.ui.theme

import androidx.compose.runtime.Composable

/**
 * 历史包装点（MainActivity 在用）：字阶已完整落在 [typography]（Type.kt），
 * 不再嵌套 MaterialTheme 覆盖——嵌套 copy 曾是字号字段丢失的风险点。
 * 保留函数与调用形态，语义 = 直通。
 */
@Composable
fun PiKATextTheme(content: @Composable () -> Unit) {
    content()
}
