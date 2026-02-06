package com.duckflix.lite.ui.components.livetv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.duckflix.lite.data.remote.dto.LiveTvProgram

/**
 * Program cell in EPG grid with dynamic width based on duration
 */
@Composable
fun ProgramCell(
    program: LiveTvProgram,
    @Suppress("UNUSED_PARAMETER") epgStartTime: Long,  // Kept for API consistency
    timeSlotWidth: Dp,         // Width per 30-minute slot
    @Suppress("UNUSED_PARAMETER") rowHeight: Dp,       // Kept for API consistency
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    // Calculate cell width based on program duration
    val programDurationMinutes = program.durationMinutes
    val cellWidth = (programDurationMinutes.toFloat() / 30f * timeSlotWidth.value).dp.coerceAtLeast(60.dp)

    // Determine if program is currently airing or past
    val isAiring = program.isCurrentlyAiring
    val now = System.currentTimeMillis() / 1000
    val isPast = program.stop < now

    val backgroundColor = when {
        isFocused -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        isAiring -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        isPast -> Color(0xFF1A1A1A)
        else -> Color(0xFF252525)
    }

    val textColor = when {
        isFocused -> Color.White
        isPast -> Color.White.copy(alpha = 0.4f)
        else -> Color.White.copy(alpha = 0.9f)
    }

    Card(
        onClick = onClick,
        modifier = modifier
            .width(cellWidth)
            .fillMaxHeight()
            .padding(horizontal = 1.dp, vertical = 2.dp)
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .focusable(interactionSource = interactionSource),
        interactionSource = interactionSource,
        shape = RoundedCornerShape(4.dp),
        border = if (isFocused) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else if (isAiring) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
        } else {
            null
        },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            // Program title
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodySmall,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Progress indicator for currently airing programs
            if (isAiring) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxHeight(0.05f)
                        .width((cellWidth.value * (program.progressPercent / 100f)).dp)
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    MaterialTheme.colorScheme.primary,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                )
                            )
                        )
                )
            }
        }
    }
}

/**
 * Calculate the horizontal offset for a program cell within the EPG grid
 */
fun calculateProgramOffset(
    programStart: Long,
    epgStartTime: Long,
    timeSlotWidth: Dp
): Dp {
    val minutesSinceEpgStart = ((programStart - epgStartTime) / 60).toFloat()
    val pixelsPerMinute = timeSlotWidth.value / 30f
    return (minutesSinceEpgStart * pixelsPerMinute).dp.coerceAtLeast(0.dp)
}
