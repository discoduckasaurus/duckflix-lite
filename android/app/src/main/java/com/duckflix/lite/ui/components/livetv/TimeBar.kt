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

    // Round epgStartTime DOWN to nearest half hour for clean time slots
    val halfHourSeconds = 30 * 60L
    val roundedStartTime = (epgStartTime / halfHourSeconds) * halfHourSeconds

    val totalMinutes = ((epgEndTime - roundedStartTime) / 60).toInt()
    val slotCount = (totalMinutes / timeSlotMinutes) + 1  // +1 to ensure we cover the full range

    // Calculate current time offset relative to rounded start
    val currentTimeOffset = calculateCurrentTimeOffset(
        roundedStartTime, currentTime, timeSlotWidth, timeSlotMinutes
    )

    // Calculate total width for time slots
    val totalTimeWidth = (slotCount * timeSlotWidth.value).dp

    // Main container with fixed height
    Row(
        modifier = modifier
            .height(40.dp)
            .background(Color(0xFF1A1A1A))
    ) {
        // Channel column placeholder with "TIME" label
        Box(
            modifier = Modifier
                .width(channelColumnWidth)
                .fillMaxHeight()
                .background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = "TIME",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // Scrollable time slots container
        Box(
            modifier = Modifier
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier
                    .width(totalTimeWidth)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScrollState)
            ) {
                repeat(slotCount) { index ->
                    val slotStartTime = roundedStartTime + (index * timeSlotMinutes * 60)
                    val isCurrentSlot = currentTime >= slotStartTime &&
                            currentTime < slotStartTime + (timeSlotMinutes * 60)

                    Box(
                        modifier = Modifier
                            .width(timeSlotWidth)
                            .fillMaxHeight()
                            .background(
                                if (isCurrentSlot) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                else Color(0xFF1A1A1A)
                            )
                    ) {
                        // Timestamp label - show on-the-hour times (:00 and :30)
                        Text(
                            text = formatTimeSlotRounded(slotStartTime),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrentSlot) Color.White else Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 8.dp)
                        )

                        // Delimiter line at right edge of each slot
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(Color(0xFF444444))
                        )
                    }
                }
            }

            // Current time indicator line (red)
            if (currentTimeOffset > 0.dp) {
                Box(
                    modifier = Modifier
                        .offset(x = currentTimeOffset - horizontalScrollState.value.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Color.Red)
                )
            }
        }
    }
}

private fun formatTimeSlotRounded(timestamp: Long): String {
    // Format showing hour and half-hour marks (e.g., "10:00 PM", "10:30 PM")
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
