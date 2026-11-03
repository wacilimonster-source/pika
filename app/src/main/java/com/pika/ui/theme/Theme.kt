package com.pika.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = TokenPrimaryDark,
    onPrimary = TokenOnPrimaryDark,
    primaryContainer = TokenPrimaryContainerDark,
    onPrimaryContainer = TokenOnPrimaryContainerDark,
    secondary = TokenSecondaryDark,
    onSecondary = TokenOnSecondaryDark,
    secondaryContainer = TokenSecondaryContainerDark,
    onSecondaryContainer = TokenOnSecondaryContainerDark,
    tertiary = TokenTertiaryDark,
    onTertiary = TokenOnTertiaryDark,
    tertiaryContainer = TokenTertiaryContainerDark,
    onTertiaryContainer = TokenOnTertiaryContainerDark,
    background = TokenBackgroundDark,
    onBackground = TokenOnBackgroundDark,
    surface = TokenSurfaceDark,
    onSurface = TokenOnSurfaceDark,
    surfaceVariant = TokenSurfaceVariantDark,
    onSurfaceVariant = TokenOnSurfaceVariantDark,
    surfaceContainer = TokenSurfaceContainerDark,
    surfaceContainerLowest = TokenSurfaceContainerLowestDark,
    surfaceContainerLow = TokenSurfaceContainerLowDark,
    surfaceContainerHigh = TokenSurfaceContainerHighDark,
    surfaceContainerHighest = TokenSurfaceContainerHighestDark,
    surfaceDim = TokenSurfaceDimDark,
    surfaceBright = TokenSurfaceBrightDark,
    inverseSurface = TokenInverseSurfaceDark,
    inverseOnSurface = TokenInverseOnSurfaceDark,
    inversePrimary = TokenInversePrimaryDark,
    outline = TokenOutlineDark,
    outlineVariant = TokenOutlineVariantDark,
    error = TokenErrorDark,
    onError = TokenOnErrorDark,
    errorContainer = TokenErrorContainerDark,
    scrim = Color.Black,
)

private val LightColorScheme = lightColorScheme(
    primary = TokenPrimaryLight,
    onPrimary = TokenOnPrimaryLight,
    primaryContainer = TokenPrimaryContainerLight,
    onPrimaryContainer = TokenOnPrimaryContainerLight,
    secondary = TokenSecondaryLight,
    onSecondary = TokenOnSecondaryLight,
    secondaryContainer = TokenSecondaryContainerLight,
    onSecondaryContainer = TokenOnSecondaryContainerLight,
    tertiary = TokenTertiaryLight,
    onTertiary = TokenOnTertiaryLight,
    tertiaryContainer = TokenTertiaryContainerLight,
    onTertiaryContainer = TokenOnTertiaryContainerLight,
    background = TokenBackgroundLight,
    onBackground = TokenOnBackgroundLight,
    surface = TokenSurfaceLight,
    onSurface = TokenOnSurfaceLight,
    surfaceVariant = TokenSurfaceVariantLight,
    onSurfaceVariant = TokenOnSurfaceVariantLight,
    surfaceContainer = TokenSurfaceContainerLight,
    surfaceContainerLowest = TokenSurfaceContainerLowestLight,
    surfaceContainerLow = TokenSurfaceContainerLowLight,
    surfaceContainerHigh = TokenSurfaceContainerHighLight,
    surfaceContainerHighest = TokenSurfaceContainerHighestLight,
    surfaceDim = TokenSurfaceDimLight,
    surfaceBright = TokenSurfaceBrightLight,
    inverseSurface = TokenInverseSurfaceLight,
    inverseOnSurface = TokenInverseOnSurfaceLight,
    inversePrimary = TokenInversePrimaryLight,
    outline = TokenOutlineLight,
    outlineVariant = TokenOutlineVariantLight,
    error = TokenErrorLight,
    onError = TokenOnErrorLight,
    errorContainer = TokenErrorContainerLight,
    scrim = Color.Black,
)

/** 语义成功色（tokens 的 success 角色，非 M3 标准角色）：下载完成等正向状态用 */
data class PiKAExtraColors(
    val success: Color,
)

private val LocalPiKAExtraColors = staticCompositionLocalOf {
    PiKAExtraColors(success = TokenSuccessDark)
}

/** 非 M3 标准角色的扩展色（success 等），随主题切换 */
val MaterialTheme.pikaExtras: PiKAExtraColors
    @Composable get() = LocalPiKAExtraColors.current

/**
 * 主题入口：继续跟随系统深浅色（isSystemInDarkTheme），不新增主题偏好。
 * 全量定义 ColorScheme 常用角色，避免只设 primary 后残留默认 Material 紫。
 */
@Composable
fun PiKATheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extras = PiKAExtraColors(
        success = if (darkTheme) TokenSuccessDark else TokenSuccessLight,
    )

    androidx.compose.runtime.CompositionLocalProvider(LocalPiKAExtraColors provides extras) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            shapes = PiKAShapes,
            content = content,
        )
    }
}

/** 形状体系：按钮/筛选 chip 10、分组卡 14、弹层 24（design-tokens layout） */
private val PiKAShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)
