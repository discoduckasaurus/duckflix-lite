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
 * Font scaling is slightly more aggressive than dimension scaling
 */
enum class UiScale(
    val displayName: String,
    val factor: Float,
    val fontFactor: Float,  // Font scales more aggressively
    val posterFactor: Float // Posters scale even more
) {
    EXTRA_SMALL("Extra Small", 0.60f, 0.65f, 0.80f),
    SMALL("Small", 0.75f, 0.80f, 0.95f),
    NORMAL("Normal", 0.90f, 0.95f, 1.15f),
    LARGE("Large", 1.05f, 1.10f, 1.35f),
    EXTRA_LARGE("Extra Large", 1.20f, 1.25f, 1.60f);

    companion object {
        fun fromFactor(factor: Float): UiScale {
            return entries.minByOrNull { kotlin.math.abs(it.factor - factor) } ?: NORMAL
        }

        fun fromOrdinal(ordinal: Int): UiScale {
            return entries.getOrNull(ordinal) ?: NORMAL
        }
    }
}

/**
 * CompositionLocal for providing UI scale factor throughout the app
 */
val LocalUiScale = compositionLocalOf { UiScale.NORMAL }

/**
 * Helper object for persisting UI scale preference
 */
object UiScalePreferences {
    private const val PREFS_NAME = "ui_preferences"
    private const val KEY_UI_SCALE = "ui_scale"

    fun getUiScale(context: Context): UiScale {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val ordinal = prefs.getInt(KEY_UI_SCALE, UiScale.NORMAL.ordinal)
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
