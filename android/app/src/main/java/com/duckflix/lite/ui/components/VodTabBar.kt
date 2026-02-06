package com.duckflix.lite.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Tab options for VOD section navigation
 */
enum class VodTab(val displayName: String) {
    MY_STUFF("My Stuff"),
    SEARCH("Search"),
    DISCOVER("Discover"),
    CLASSIC_MODE("Classic Mode")
}

/**
 * Horizontal tab bar for VOD section navigation
 * Optimized for TV D-pad navigation with clear focus states
 */
@Composable
fun VodTabBar(
    tabs: List<VodTab>,
    selectedTab: VodTab,
    onTabSelected: (VodTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        tabs.forEach { tab ->
            VodTabItem(
                tab = tab,
                isSelected = tab == selectedTab,
                onSelect = { onTabSelected(tab) }
            )
        }
    }
}

/**
 * Individual tab item within the VodTabBar
 */
@Composable
private fun RowScope.VodTabItem(
    tab: VodTab,
    isSelected: Boolean,
    onSelect: () -> Unit,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(8.dp)

    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    val textColor = when {
        isSelected -> Color.White
        else -> Color.White.copy(alpha = 0.7f)
    }

    val borderModifier = if (isFocused) {
        Modifier.border(
            BorderStroke(4.dp, MaterialTheme.colorScheme.primary),
            shape
        )
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .weight(1f)
            .height(56.dp)
            .clip(shape)
            .then(borderModifier)
            .background(backgroundColor, shape)
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = tab.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = textColor
        )
    }
}
