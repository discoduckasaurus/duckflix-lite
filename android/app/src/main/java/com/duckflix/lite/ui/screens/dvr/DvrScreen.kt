package com.duckflix.lite.ui.screens.dvr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.duckflix.lite.data.local.entity.RecordingEntity
import com.duckflix.lite.ui.components.LogoButton
import com.duckflix.lite.ui.components.TVSafeArea
import com.duckflix.lite.ui.theme.Dimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DvrScreen(
    onNavigateBack: () -> Unit,
    onPlayRecording: (filePath: String, title: String) -> Unit,
    viewModel: DvrViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp && keyEvent.key in listOf(Key.Back, Key.Escape)) {
                    onNavigateBack()
                    true
                } else false
            }
            .focusable()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = TVSafeArea.HorizontalPadding,
                    vertical = TVSafeArea.VerticalPadding
                )
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DVR RECORDINGS",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.weight(1f))

                // Storage info
                Text(
                    text = "${formatBytes(uiState.availableSpace)} free (${uiState.storageType})",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f)
                )

                LogoButton(
                    onClick = onNavigateBack,
                    size = Dimens.tabBarHeight
                )
            }

            // Tabs
            val tabs = listOf(
                "Active (${uiState.activeRecordings.size})",
                "Scheduled (${uiState.scheduledRecordings.size})",
                "Library (${uiState.completedRecordings.size})"
            )
            TabRow(
                selectedTabIndex = uiState.selectedTab,
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.White
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = uiState.selectedTab == index,
                        onClick = { viewModel.selectTab(index) },
                        text = {
                            Text(
                                text = title,
                                color = if (uiState.selectedTab == index) Color.White else Color.White.copy(alpha = 0.5f)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content based on selected tab
            when (uiState.selectedTab) {
                0 -> RecordingsList(
                    recordings = uiState.activeRecordings,
                    emptyMessage = "No active recordings",
                    onAction = { viewModel.cancelRecording(it) },
                    actionLabel = "Stop",
                    actionColor = Color(0xFFE53935)
                )
                1 -> RecordingsList(
                    recordings = uiState.scheduledRecordings,
                    emptyMessage = "No scheduled recordings",
                    onAction = { viewModel.cancelRecording(it) },
                    actionLabel = "Cancel",
                    actionColor = Color(0xFFFF9800)
                )
                2 -> RecordingsList(
                    recordings = uiState.completedRecordings + uiState.failedRecordings,
                    emptyMessage = "No recordings yet. Record from the Live TV EPG guide.",
                    onAction = { viewModel.deleteRecording(it) },
                    actionLabel = "Delete",
                    actionColor = Color(0xFFE53935),
                    onPlay = { recording ->
                        if (recording.status == "completed" && recording.filePath != null) {
                            onPlayRecording(recording.filePath, "${recording.channelName} - ${recording.programTitle}")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun RecordingsList(
    recordings: List<RecordingEntity>,
    emptyMessage: String,
    onAction: (RecordingEntity) -> Unit,
    actionLabel: String,
    actionColor: Color,
    onPlay: ((RecordingEntity) -> Unit)? = null
) {
    if (recordings.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            items(recordings, key = { it.id }) { recording ->
                RecordingCard(
                    recording = recording,
                    onAction = { onAction(recording) },
                    actionLabel = actionLabel,
                    actionColor = actionColor,
                    onPlay = onPlay?.let { { it(recording) } }
                )
            }
        }
    }
}

@Composable
private fun RecordingCard(
    recording: RecordingEntity,
    onAction: () -> Unit,
    actionLabel: String,
    actionColor: Color,
    onPlay: (() -> Unit)? = null
) {
    val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
            .then(
                if (onPlay != null && recording.status == "completed") {
                    Modifier.clickable { onPlay() }
                } else Modifier
            )
            .focusable()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Status badge
                val (statusText, statusColor) = when (recording.status) {
                    "recording" -> "REC" to Color(0xFFE53935)
                    "scheduled" -> "SCHEDULED" to Color(0xFF2196F3)
                    "completed" -> "COMPLETED" to Color(0xFF4CAF50)
                    "failed" -> "FAILED" to Color(0xFFFF9800)
                    "cancelled" -> "CANCELLED" to Color.Gray
                    else -> recording.status.uppercase() to Color.Gray
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(statusColor.copy(alpha = 0.2f))
                            .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = recording.channelName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.6f)
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = recording.programTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                recording.programDescription?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = dateFormat.format(Date(recording.scheduledStart)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    val durationMin = ((recording.scheduledEnd - recording.scheduledStart) / 60000).toInt()
                    Text(
                        text = "${durationMin}min",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.4f)
                    )
                    if (recording.fileSize > 0) {
                        Text(
                            text = formatBytes(recording.fileSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.4f)
                        )
                    }
                }

                recording.errorMessage?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFFF9800),
                        maxLines = 1
                    )
                }

                // Progress bar for active recordings
                if (recording.status == "recording") {
                    Spacer(modifier = Modifier.height(8.dp))
                    val elapsed = System.currentTimeMillis() - recording.scheduledStart
                    val total = recording.scheduledEnd - recording.scheduledStart
                    val progress = (elapsed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = Color(0xFFE53935),
                        trackColor = Color.White.copy(alpha = 0.1f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onPlay != null && recording.status == "completed") {
                    Button(
                        onClick = onPlay,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Text("Play")
                    }
                }
                Button(
                    onClick = onAction,
                    colors = ButtonDefaults.buttonColors(containerColor = actionColor)
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}
