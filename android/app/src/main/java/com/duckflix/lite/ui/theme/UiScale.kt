package com.duckflix.lite.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * UI Scale presets for adjusting the overall size of UI elements
 * All elements scale uniformly
 */
enum class UiScale(
    val displayName: String,
    val factor: Float,
    val fontFactor: Float,
    val posterFactor: Float
) {
    REGULAR("Regular", 1.00f, 1.00f, 1.00f),
    ZOOMED("Zoomed", 1.20f, 1.44f, 1.20f);

    companion object {
        fun fromFactor(factor: Float): UiScale {
            return entries.minByOrNull { kotlin.math.abs(it.factor - factor) } ?: REGULAR
        }

        fun fromOrdinal(ordinal: Int): UiScale {
            // Migration: old ordinals 0-2 (XS, S, Normal) -> REGULAR, 3-4 (L, XL) -> ZOOMED
            return when (ordinal) {
                0, 1, 2 -> REGULAR  // Old Extra Small, Small, Normal -> Regular
                3, 4 -> ZOOMED      // Old Large, Extra Large -> Zoomed
                else -> entries.getOrNull(ordinal) ?: REGULAR
            }
        }
    }
}

/**
 * CompositionLocal for providing UI scale factor throughout the app
 */
val LocalUiScale = compositionLocalOf { UiScale.REGULAR }

/**
 * Helper object for persisting UI scale preference
 */
object UiScalePreferences {
    private const val PREFS_NAME = "ui_preferences"
    private const val KEY_UI_SCALE = "ui_scale"

    fun getUiScale(context: Context): UiScale {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ordinal = prefs.getInt(KEY_UI_SCALE, UiScale.REGULAR.ordinal)
        return UiScale.fromOrdinal(ordinal)
    }

    fun setUiScale(context: Context, scale: UiScale) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_UI_SCALE, scale.ordinal).apply()
    }
}

/**
 * Extension function to scale Dp values based on current UI scale
 */
@Composable
fun Dp.scaled(): Dp {
    val scale = LocalUiScale.current
    return (this.value * scale.factor).dp
}

/**
 * Extension function to scale TextUnit (sp) values based on current UI scale
 * Uses fontFactor for more aggressive font scaling
 */
@Composable
fun TextUnit.scaled(): TextUnit {
    val scale = LocalUiScale.current
    return (this.value * scale.fontFactor).sp
}

/**
 * Scale a Dp value for posters (more aggressive scaling)
 */
@Composable
fun Dp.scaledPoster(): Dp {
    val scale = LocalUiScale.current
    return (this.value * scale.posterFactor).dp
}

/**
 * Scale a raw dp value
 */
@Composable
fun scaledDp(value: Float): Dp {
    val scale = LocalUiScale.current
    return (value * scale.factor).dp
}

/**
 * Scale a raw sp value
 */
@Composable
fun scaledSp(value: Float): TextUnit {
    val scale = LocalUiScale.current
    return (value * scale.fontFactor).sp
}

/**
 * Get the current scale factor directly
 */
@Composable
fun currentScaleFactor(): Float {
    return LocalUiScale.current.factor
}

/**
 * Get the current font scale factor
 */
@Composable
fun currentFontScaleFactor(): Float {
    return LocalUiScale.current.fontFactor
}
