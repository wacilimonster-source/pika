package com.pika.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 字阶（design-tokens.json typography）：
 * 页面标题 22/28·600、区块标题 18/24·600、详情标题 24/32·600、
 * 正文 14/22、次级 12/18、辅助 11/16。系统无衬线字体，不引入字体 SDK。
 * 网格卡片标题（两列 14/20·600、三列 12/18·600）由 ComicGridView 按列数取用。
 */
private val sans = FontFamily.SansSerif

val typography = Typography(
    // 详情标题 24/32·600（headlineSmall 默认即 24/32，显式固化字重）
    headlineLarge = TextStyle(fontFamily = sans, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = sans, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    // 页面标题 22/28·600
    titleLarge = TextStyle(fontFamily = sans, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    // 区块标题 18/24·600
    titleMedium = TextStyle(fontFamily = sans, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    // 卡片标题（两列网格 14/20·600；设置行/列表标题沿用）
    titleSmall = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    // 正文 14/22
    bodyLarge = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal),
    // 次级正文 14/20（列表行/说明文字）
    bodyMedium = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    // 次级 12/18
    bodySmall = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    // 按钮标签/强调行内标签
    labelLarge = TextStyle(fontFamily = sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    // 辅助 12/16
    labelMedium = TextStyle(fontFamily = sans, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    // 辅助最小 11/16（角标可到 10sp，但不承载唯一操作/必要正文）
    labelSmall = TextStyle(fontFamily = sans, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)
