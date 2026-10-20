package com.ariateam.aria.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = AriaSky,
    onPrimary = Color.Black,
    background = AriaNavy,
    surface = AriaPanel,
    onBackground = Color.White,
    onSurface = Color(0xFFE2E8F0),
    onSurfaceVariant = AriaSlate,
    surfaceVariant = AriaPanel,
    error = Color(0xFFF87171)
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF0056B3),
    onPrimary = Color.White,
    background = Color(0xFFF2F2F7),
    surface = Color.White,
    onBackground = Color(0xFF1C1C1E),
    onSurface = Color(0xFF1C1C1E)
)

@Composable
fun AriaTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}