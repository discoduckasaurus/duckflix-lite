package com.duckflix.lite.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * TV Safe Area wrapper for Android TV overscan compensation.
 *
 * Modern smart TVs have minimal overscan. Most streaming apps (Netflix, etc.)
 * use aggressive margins of 16-24dp. Users can adjust TV settings for overscan.
 *
 * Using minimal safe zones to maximize screen real estate on modern displays.
 */
object TVSafeArea {
    // Horizontal safe padding (left/right) - aggressive for modern TVs
    val HorizontalPadding = 16.dp

    // Vertical safe padding (top/bottom) - aggressive for modern TVs
    val VerticalPadding = 12.dp
}

/**
 * Wraps content in TV-safe padding to account for overscan.
 * Use this for main screen content that needs to stay visible on all TVs.
 */
@Composable
fun TVSafeContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(
                horizontal = TVSafeArea.HorizontalPadding,
                vertical = TVSafeArea.VerticalPadding
            )
    ) {
        content()
    }
}
