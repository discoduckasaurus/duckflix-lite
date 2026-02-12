package com.duckflix.lite.ui.components.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.duckflix.lite.data.remote.dto.LiveTvChannel

/**
 * EPG Grid with synchronized horizontal scrolling between time bar and program cells
 * Vertical scrolling for channels, horizontal scrolling for time
 */
@Composable
fun EpgGrid(
    channels: List<LiveTvChannel>,
    selectedChannel: LiveTvChannel?,
    epgStartTime: Long,
    epgEndTime: Long,
    currentTime: Long,
    onChannelClick: (LiveTvChannel) -> Unit,
    onProgramClick: (LiveTvChannel, com.duckflix.lite.data.remote.dto.LiveTvProgram) -> Unit,
    modifier: Modifier = Modifier,
    channelColumnWidth: Dp = 180.dp,
    timeSlotWidth: Dp = 150.dp,    // Width per 30-minute slot
    rowHeight: Dp = 64.dp,
    focusTrigger: Int = 0  // Changes to trigger focus restoration
) {
    // Shared horizontal scroll state for time bar and program grid
    val horizontalScrollState = rememberScrollState()
    val verticalListState = rememberLazyListState()

    // Track whether initial focus has been requested (only once per screen load)
    val shouldRequestInitialFocus = remember { mutableStateOf(true) }

    // Scroll to current time on first load
    LaunchedEffect(epgStartTime, currentTime) {
        if (epgStartTime > 0 && currentTime > epgStartTime) {
            val minutesSinceStart = ((currentTime - epgStartTime) / 60).toFloat()
            val pixelsPerMinute = timeSlotWidth.value / 30f
            val scrollOffset = (minutesSinceStart * pixelsPerMinute).toInt()
            // Scroll to 1 hour before current time for context
            horizontalScrollState.animateScrollTo((scrollOffset - 200).coerceAtLeast(0))
        }
    }

    // Mark initial focus as done after a delay (to prevent re-triggering)
    LaunchedEffect(shouldRequestInitialFocus.value) {
        if (shouldRequestInitialFocus.value) {
            kotlinx.coroutines.delay(500)
            shouldRequestInitialFocus.value = false
        }
    }

    // Reset focus request when returning from fullscreen (focusTrigger changes)
    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) {
            println("[EpgGrid] Focus trigger changed to $focusTrigger, resetting initial focus")
            shouldRequestInitialFocus.value = true
        }
    }

    // Debug logging
    println("[EpgGrid] Rendering with ${channels.size} channels, epgStart=$epgStartTime, epgEnd=$epgEndTime")

    Column(modifier = modifier) {
        // Time bar (sticky at top)
        TimeBar(
            epgStartTime = epgStartTime,
            epgEndTime = epgEndTime,
            currentTime = currentTime,
            timeSlotWidth = timeSlotWidth,
            horizontalScrollState = horizontalScrollState,
            channelColumnWidth = channelColumnWidth
        )

        HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)

        // Channel rows with program cells
        LazyColumn(
            state = verticalListState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)  // Take remaining vertical space
        ) {
            itemsIndexed(
                items = channels,
                key = { _, channel -> channel.id }
            ) { index, channel ->
                if (index < 3) {
                    println("[EpgGrid] Rendering channel item $index: ${channel.effectiveDisplayName}")
                }
                val isSelected = channel.id == selectedChannel?.id

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .background(
                            if (isSelected) Color(0xFF1A2A1A) else Color.Transparent
                        )
                ) {
                    // Channel info (sticky left column)
                    // Request focus on selected channel, or first channel during initial load if no channel is selected
                    val shouldFocus = when {
                        isSelected -> true  // Focus selected channel (initial load after auto-select, or returning from fullscreen)
                        index == 0 && shouldRequestInitialFocus.value && selectedChannel == null -> true  // Focus first channel on initial load before auto-select
                        else -> false
                    }
                    ChannelRow(
                        channel = channel,
                        isSelected = isSelected,
                        onClick = { onChannelClick(channel) },
                        width = channelColumnWidth,
                        height = rowHeight,
                        displayNumber = index + 1,  // 1-based position in list
                        requestInitialFocus = shouldFocus,
                        focusTrigger = focusTrigger  // Pass trigger to re-focus on return from fullscreen
                    )

                    // Program cells (horizontally scrollable)
                    ProgramRow(
                        channel = channel,
                        epgStartTime = epgStartTime,
                        epgEndTime = epgEndTime,
                        timeSlotWidth = timeSlotWidth,
                        rowHeight = rowHeight,
                        horizontalScrollState = horizontalScrollState,
                        onProgramClick = { program -> onProgramClick(channel, program) },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Divider between rows
                HorizontalDivider(
                    color = Color(0xFF2A2A2A),
                    thickness = 1.dp,
                    modifier = Modifier.padding(start = channelColumnWidth)
                )
            }
        }
    }
}

/**
 * Row of program cells for a single channel
 */
@Composable
private fun ProgramRow(
    channel: LiveTvChannel,
    epgStartTime: Long,
    epgEndTime: Long,
    timeSlotWidth: Dp,
    rowHeight: Dp,
    horizontalScrollState: ScrollState,
    onProgramClick: (com.duckflix.lite.data.remote.dto.LiveTvProgram) -> Unit,
    modifier: Modifier = Modifier
) {
    // Calculate total grid width
    val totalMinutes = ((epgEndTime - epgStartTime) / 60).toInt()
    val gridWidth = (totalMinutes.toFloat() / 30f * timeSlotWidth.value).dp

    // Outer box is the viewport (takes remaining width via modifier)
    // Inner box is the scrollable content with full grid width
    Box(
        modifier = modifier
            .height(rowHeight)
            .clipToBounds()
            .horizontalScroll(horizontalScrollState)
    ) {
        // Inner content box with full grid width
        Box(
            modifier = Modifier
                .width(gridWidth)
                .height(rowHeight)
        ) {
            // Position each program based on its start time
            channel.allPrograms
                .filter { it.stop > epgStartTime && it.start < epgEndTime }
                .forEach { program ->
                    // Clamp program times to EPG window
                    val displayStart = maxOf(program.start, epgStartTime)
                    val displayEnd = minOf(program.stop, epgEndTime)
                    val displayDurationMinutes = ((displayEnd - displayStart) / 60).toInt()

                    val offset = calculateProgramOffset(displayStart, epgStartTime, timeSlotWidth)
                    Box(
                        modifier = Modifier.offset(x = offset)
                    ) {
                        ProgramCell(
                            program = program,
                            displayDurationMinutes = displayDurationMinutes,
                            timeSlotWidth = timeSlotWidth,
                            onClick = { onProgramClick(program) },
                            horizontalScrollState = horizontalScrollState,
                            offsetDp = offset
                        )
                    }
                }

            // Fill empty space if no programs
            if (channel.allPrograms.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(Color(0xFF1A1A1A))
                )
            }
        }
    }
}
