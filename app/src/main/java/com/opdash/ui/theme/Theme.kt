package com.opdash.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DashDarkColorScheme = darkColorScheme(
    primary = AccentOrange,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3D2000),
    onPrimaryContainer = AccentAmber,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    secondaryContainer = DashSurfaceVariant,
    onSecondaryContainer = AccentCyan,
    tertiary = AccentGreen,
    background = DashBlack,
    onBackground = TextPrimary,
    surface = DashSurface,
    onSurface = TextPrimary,
    surfaceVariant = DashSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    error = AccentRed,
    onError = Color.Black,
    outline = TextDim,
    outlineVariant = Color(0xFF2A2A2A)
)

@Composable
fun OPDashV2Theme(
    content: @Composable () -> Unit
) {
    // Always dark — this is a motorcycle dash app
    MaterialTheme(
        colorScheme = DashDarkColorScheme,
        typography = Typography,
        content = content
    )
}