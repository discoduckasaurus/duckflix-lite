package com.duckflix.lite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF1E88E5),
    secondary = Color(0xFFFF6F00),
    tertiary = Color(0xFF4CAF50),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun DuckFlixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    uiScale: UiScale? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val resolvedScale = uiScale ?: remember { UiScalePreferences.getUiScale(context) }

    CompositionLocalProvider(LocalUiScale provides resolvedScale) {
        MaterialTheme(
            colorScheme = DarkColorScheme,  // Always use dark theme for TV
            typography = Typography,
            content = content
        )
    }
}
