package com.duckflix.lite.ui.components.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time bar showing time slots for EPG grid
 * Stays sticky at top while EPG scrolls horizontally
 */
@Composable
fun TimeBar(
    epgStartTime: Long,      // Unix timestamp (seconds)
    epgEndTime: Long,        // Unix timestamp (seconds)
    currentTime: Long,       // Unix timestamp (seconds)
    timeSlotWidth: Dp,       // Width per 30-minute slot
    horizontalScrollState: ScrollState,
    channelColumnWidth: Dp,  // Width of the channel name column
    modifier: Modifier = Modifier
) {
    val timeSlotMinutes = 30
    val totalMinutes = ((epgEndTime - epgStartTime) / 60).toInt()
    val slotCount = totalMinutes / timeSlotMinutes

    // Calculate current time offset for the red indicator line
    val currentTimeOffset = calculateCurrentTimeOffset(
        epgStartTime, currentTime, timeSlotWidth, timeSlotMinutes
    )

    // Wrap everything in a Box so TimeBar only emits one root composable
    Box(
        modifier = modifier
            .height(40.dp)
            .background(Color(0xFF1A1A1A))
    ) {
        Row(
            modifier = Modifier.matchParentSize()
        ) {
            // Empty space for channel column alignment
            Box(
                modifier = Modifier
                    .width(channelColumnWidth)
                    .fillMaxHeight()
                    .background(Color(0xFF1A1A1A))
            ) {
                Text(
                    text = "TIME",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                )
            }

            // Time slots (scrollable, synchronized with EPG)
            Row(
                modifier = Modifier
                    .horizontalScroll(horizontalScrollState)
            ) {
                repeat(slotCount) { index ->
                    val slotStartTime = epgStartTime + (index * timeSlotMinutes * 60)
                    val isCurrentSlot = currentTime >= slotStartTime &&
                            currentTime < slotStartTime + (timeSlotMinutes * 60)

                    Box(
                        modifier = Modifier
                            .width(timeSlotWidth)
                            .fillMaxHeight()
                            .background(
                                if (isCurrentSlot) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                else Color.Transparent
                            ),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = formatTimeSlot(slotStartTime),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (isCurrentSlot) MaterialTheme.colorScheme.primary
                                   else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }

        // Current time indicator line (inside the Box, won't affect parent Column layout)
        if (currentTimeOffset > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = channelColumnWidth + currentTimeOffset - horizontalScrollState.value.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.Red)
            )
        }
    }
}

private fun formatTimeSlot(timestamp: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date(timestamp * 1000))
}

private fun calculateCurrentTimeOffset(
    epgStartTime: Long,
    currentTime: Long,
    timeSlotWidth: Dp,
    timeSlotMinutes: Int
): Dp {
    if (currentTime < epgStartTime) return 0.dp
    val minutesSinceStart = ((currentTime - epgStartTime) / 60).toFloat()
    val pixelsPerMinute = timeSlotWidth.value / timeSlotMinutes
    return (minutesSinceStart * pixelsPerMinute).dp
}
