package com.wenxu.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val WenxuLightColors = lightColorScheme(
    primary = WenxuBrand,
    onPrimary = Color.White,
    primaryContainer = WenxuBrandSoft,
    onPrimaryContainer = WenxuInk,
    secondary = WenxuAmber,
    onSecondary = Color.White,
    secondaryContainer = WenxuAmberSoft,
    onSecondaryContainer = WenxuInk,
    background = WenxuBackground,
    onBackground = WenxuInk,
    surface = WenxuSurface,
    onSurface = WenxuInk,
    surfaceVariant = WenxuBrandSoft,
    onSurfaceVariant = WenxuMuted,
    outline = WenxuLine,
    error = WenxuDanger,
    onError = Color.White,
    errorContainer = WenxuDangerSoft,
    onErrorContainer = WenxuInk,
)

private val WenxuShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(DocumentAssistantDimens.ButtonCorner),
    large = RoundedCornerShape(DocumentAssistantDimens.WorkbenchCorner),
)

@Composable
fun WenxuTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WenxuLightColors,
        typography = WenxuTypography,
        shapes = WenxuShapes,
        content = content,
    )
}
