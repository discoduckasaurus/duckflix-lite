package com.duckflix.lite.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Centralized scaled dimensions for the DuckFlix UI.
 * All values scale based on the current UiScale setting.
 *
 * Usage: Dimens.posterWidth, Dimens.menuTileHeight, etc.
 */
object Dimens {

    // ==================== Poster Dimensions ====================
    // Posters use more aggressive scaling (posterFactor)

    /** Standard poster width for carousels */
    val posterWidth: Dp
        @Composable get() = 140.dp.scaledPoster()

    /** Standard poster height (2:3 aspect ratio) */
    val posterHeight: Dp
        @Composable get() = 210.dp.scaledPoster()

    /** Small poster width for compact views */
    val posterSmallWidth: Dp
        @Composable get() = 120.dp.scaledPoster()

    /** Small poster height */
    val posterSmallHeight: Dp
        @Composable get() = 180.dp.scaledPoster()

    /** Large poster width for detail screens */
    val posterLargeWidth: Dp
        @Composable get() = 200.dp.scaledPoster()

    /** Large poster height */
    val posterLargeHeight: Dp
        @Composable get() = 300.dp.scaledPoster()

    /** Poster card width (for carousels with text below) */
    val posterCardWidth: Dp
        @Composable get() = 128.dp.scaledPoster()

    /** Poster card total height (poster + text area) */
    val posterCardHeight: Dp
        @Composable get() = 240.dp.scaledPoster()

    /** Poster image height within card (leaves room for text) */
    val posterImageHeight: Dp
        @Composable get() = 190.dp.scaledPoster()

    // ==================== Episode Card Dimensions ====================

    /** Episode card width (16:9 thumbnail) */
    val episodeCardWidth: Dp
        @Composable get() = 280.dp.scaled()

    /** Episode card height */
    val episodeCardHeight: Dp
        @Composable get() = 158.dp.scaled()

    // ==================== Menu & Navigation ====================

    /** Top menu tile height */
    val menuTileHeight: Dp
        @Composable get() = 56.dp.scaled()

    /** Tab bar height (VOD tabs) */
    val tabBarHeight: Dp
        @Composable get() = 56.dp.scaled()

    /** Logo button size (matches menu tile height) */
    val logoButtonSize: Dp
        @Composable get() = 56.dp.scaled()

    // ==================== Spacing ====================

    /** Standard horizontal padding for screens */
    val screenPaddingHorizontal: Dp
        @Composable get() = 32.dp.scaled()

    /** Edge padding (smaller, for edge-to-edge rows) */
    val edgePadding: Dp
        @Composable get() = 16.dp.scaled()

    /** Standard vertical padding */
    val screenPaddingVertical: Dp
        @Composable get() = 24.dp.scaled()

    /** Space between items in a row */
    val itemSpacing: Dp
        @Composable get() = 12.dp.scaled()

    /** Space between sections/rows */
    val sectionSpacing: Dp
        @Composable get() = 24.dp.scaled()

    /** Small spacing */
    val spacingSmall: Dp
        @Composable get() = 8.dp.scaled()

    /** Medium spacing */
    val spacingMedium: Dp
        @Composable get() = 16.dp.scaled()

    // ==================== Buttons ====================

    /** Standard button height */
    val buttonHeight: Dp
        @Composable get() = 48.dp.scaled()

    /** Large button height */
    val buttonHeightLarge: Dp
        @Composable get() = 56.dp.scaled()

    /** Icon button size */
    val iconButtonSize: Dp
        @Composable get() = 48.dp.scaled()

    // ==================== Cards ====================

    /** Standard card corner radius */
    val cardCornerRadius: Dp
        @Composable get() = 8.dp.scaled()

    /** Card internal padding */
    val cardPadding: Dp
        @Composable get() = 16.dp.scaled()

    // ==================== Fixed Values (don't scale) ====================

    /** Focus border width - stays constant for visibility */
    val focusBorderWidth: Dp = 4.dp

    /** Minimum touch target - stays constant for accessibility */
    val minTouchTarget: Dp = 48.dp
}

/**
 * Scaled text sizes for use outside of MaterialTheme typography
 */
object TextSizes {

    /** Section header text size */
    val sectionHeader: TextUnit
        @Composable get() = 24.sp.scaled()

    /** Card title text size */
    val cardTitle: TextUnit
        @Composable get() = 16.sp.scaled()

    /** Card subtitle text size */
    val cardSubtitle: TextUnit
        @Composable get() = 14.sp.scaled()

    /** Small label text size */
    val labelSmall: TextUnit
        @Composable get() = 12.sp.scaled()

    /** Button text size */
    val button: TextUnit
        @Composable get() = 16.sp.scaled()

    /** Menu tile text size (larger for top nav) */
    val menuTile: TextUnit
        @Composable get() = 20.sp.scaled()

    /** Tab text size */
    val tab: TextUnit
        @Composable get() = 18.sp.scaled()
}
