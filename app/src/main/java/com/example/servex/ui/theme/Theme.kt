package com.example.servex.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ServexDarkScheme = darkColorScheme(
    primary = ServexColors.primary,
    onPrimary = ServexColors.background,
    background = ServexColors.background,
    onBackground = ServexColors.onSurface,
    surface = ServexColors.surface,
    onSurface = ServexColors.onSurface,
    surfaceVariant = ServexColors.surfaceVariant,
    onSurfaceVariant = ServexColors.onSurfaceVariant,
    error = ServexColors.error,
)

@Composable
fun ServexTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ServexDarkScheme, typography = ServexTypography, content = content)
}
