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
 * Most TVs have overscan that cuts off 5-10% of the screen edges.
 * This wrapper ensures content stays within the visible area.
 *
 * Standard Android TV safe area is 48dp minimum, but 60-80dp is recommended
 * for better compatibility with older TVs.
 */
object TVSafeArea {
    // Horizontal safe padding (left/right)
    val HorizontalPadding = 32.dp

    // Vertical safe padding (top/bottom)
    val VerticalPadding = 24.dp
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
