package com.duckflix.lite.ui.components.livetv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.duckflix.lite.data.remote.dto.LiveTvChannel
import com.duckflix.lite.data.remote.dto.LiveTvProgram
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Panel showing current program information for the selected channel
 */
@Composable
fun CurrentProgramInfo(
    channel: LiveTvChannel?,
    modifier: Modifier = Modifier
) {
    val program = channel?.currentProgram

    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (channel != null && program != null) {
            // Program title
            Text(
                text = program.title,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Channel name
            Text(
                text = channel.effectiveDisplayName,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Time range
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTime(program.start),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = " - ",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
                Text(
                    text = formatTime(program.stop),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.width(16.dp))

                // Category (if available)
                program.category?.let { category ->
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Progress bar
            if (program.isCurrentlyAiring) {
                LinearProgressIndicator(
                    progress = { program.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Description
            program.description?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (channel != null) {
            // No program info available
            Text(
                text = channel.effectiveDisplayName,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Text(
                text = "No program information available",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.5f)
            )
        } else {
            // No channel selected
            Text(
                text = "Select a channel",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return formatter.format(Date(timestamp * 1000))
}
