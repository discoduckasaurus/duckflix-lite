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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
    firstChannelFocusRequester: FocusRequester? = null
) {
    // Shared horizontal scroll state for time bar and program grid
    val horizontalScrollState = rememberScrollState()
    val verticalListState = rememberLazyListState()

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
                    ChannelRow(
                        channel = channel,
                        isSelected = isSelected,
                        onClick = { onChannelClick(channel) },
                        width = channelColumnWidth,
                        height = rowHeight,
                        focusRequester = if (index == 0) firstChannelFocusRequester else null
                    )

                    // Program cells (horizontally scrollable)
                    ProgramRow(
                        channel = channel,
                        epgStartTime = epgStartTime,
                        epgEndTime = epgEndTime,
                        timeSlotWidth = timeSlotWidth,
                        rowHeight = rowHeight,
                        horizontalScrollState = horizontalScrollState,
                        onProgramClick = { program -> onProgramClick(channel, program) }
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
    onProgramClick: (com.duckflix.lite.data.remote.dto.LiveTvProgram) -> Unit
) {
    // Calculate total grid width
    val totalMinutes = ((epgEndTime - epgStartTime) / 60).toInt()
    val gridWidth = (totalMinutes.toFloat() / 30f * timeSlotWidth.value).dp

    Box(
        modifier = Modifier
            .width(gridWidth)
            .height(rowHeight)
            .horizontalScroll(horizontalScrollState)
    ) {
        // Position each program based on its start time
        channel.allPrograms
            .filter { it.stop > epgStartTime && it.start < epgEndTime }
            .forEach { program ->
                // Clamp program start time to EPG window for positioning
                val displayStart = maxOf(program.start, epgStartTime)
                val offset = calculateProgramOffset(displayStart, epgStartTime, timeSlotWidth)

                Box(
                    modifier = Modifier.offset(x = offset)
                ) {
                    ProgramCell(
                        program = program,
                        epgStartTime = epgStartTime,
                        timeSlotWidth = timeSlotWidth,
                        rowHeight = rowHeight,
                        onClick = { onProgramClick(program) }
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
