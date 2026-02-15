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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.duckflix.lite.data.remote.dto.LiveTvChannel
import kotlinx.coroutines.launch

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
    val coroutineScope = rememberCoroutineScope()

    // Track whether initial focus has been requested (only once per screen load)
    val shouldRequestInitialFocus = remember { mutableStateOf(true) }

    // Wrap-around navigation state
    val pendingWrapIndex = remember { mutableStateOf<Int?>(null) }
    val wrapTrigger = remember { mutableIntStateOf(0) }

    // Combined trigger for ChannelRow focus (fullscreen return + wrap-around)
    val effectiveFocusTrigger = focusTrigger + wrapTrigger.intValue

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

    // Reset focus request and scroll to selected channel when returning from fullscreen
    LaunchedEffect(focusTrigger) {
        if (focusTrigger > 0) {
            println("[EpgGrid] Focus trigger changed to $focusTrigger, restoring scroll + focus")
            // Scroll to selected channel so it's visible before focus request
            if (selectedChannel != null) {
                val selectedIndex = channels.indexOfFirst { it.id == selectedChannel.id }
                if (selectedIndex >= 0) {
                    verticalListState.scrollToItem(selectedIndex)
                }
            }
            shouldRequestInitialFocus.value = true
        }
    }

    // Clear wrap focus target after it's been applied
    LaunchedEffect(wrapTrigger.intValue) {
        if (pendingWrapIndex.value != null) {
            kotlinx.coroutines.delay(500)
            pendingWrapIndex.value = null
        }
    }

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
                val isSelected = channel.id == selectedChannel?.id

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .background(
                            if (isSelected) Color(0xFF1A2A1A) else Color.Transparent
                        )
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                when (keyEvent.key) {
                                    Key.DirectionUp -> {
                                        if (index == 0) {
                                            // Wrap to last channel
                                            coroutineScope.launch {
                                                verticalListState.scrollToItem(channels.size - 1)
                                                pendingWrapIndex.value = channels.size - 1
                                                wrapTrigger.intValue++
                                            }
                                            true
                                        } else false
                                    }
                                    Key.DirectionDown -> {
                                        if (index == channels.size - 1) {
                                            // Wrap to first channel
                                            coroutineScope.launch {
                                                verticalListState.scrollToItem(0)
                                                pendingWrapIndex.value = 0
                                                wrapTrigger.intValue++
                                            }
                                            true
                                        } else false
                                    }
                                    else -> false
                                }
                            } else false
                        }
                ) {
                    // Channel info (sticky left column)
                    // Request focus on: wrap target, selected channel, or first channel on initial load
                    val shouldFocus = when {
                        pendingWrapIndex.value == index -> true
                        isSelected && shouldRequestInitialFocus.value -> true
                        index == 0 && shouldRequestInitialFocus.value && selectedChannel == null -> true
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
                        focusTrigger = effectiveFocusTrigger
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
