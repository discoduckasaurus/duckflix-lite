package com.duckflix.lite.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.duckflix.lite.R
import com.duckflix.lite.ui.theme.tvOsFocusEffects

/**
 * Circular logo button with DuckFlix duck icon
 * Used in top navigation bar, shows focus border when selected
 */
@Composable
fun LogoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Image(
        painter = painterResource(id = R.drawable.dfl_logo),
        contentDescription = "DuckFlix Logo",
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .tvOsFocusEffects(
                isFocused = isFocused,
                respectTransparency = true
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .then(
                if (isFocused) {
                    Modifier.border(1.5.dp, Color.White, CircleShape)
                } else {
                    Modifier
                }
            )
    )
}
