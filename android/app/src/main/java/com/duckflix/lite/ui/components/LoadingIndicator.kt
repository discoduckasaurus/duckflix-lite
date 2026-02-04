package com.duckflix.lite.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.duckflix.lite.utils.LoadingPhraseGenerator
import kotlinx.coroutines.delay

/**
 * Fullscreen loading indicator with animated phrases
 */
@Composable
fun LoadingScreen(
    message: String? = null,
    modifier: Modifier = Modifier,
    useAnimatedPhrases: Boolean = true
) {
    val generator = remember { LoadingPhraseGenerator() }
    var currentPhrase by remember { mutableStateOf(generator.generatePhrase()) }

    // Animate phrases every 800ms
    LaunchedEffect(useAnimatedPhrases) {
        if (useAnimatedPhrases) {
            while (true) {
                delay(800)
                currentPhrase = generator.generatePhrase()
            }
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.padding(bottom = 48.dp) // Add bottom padding for TV overscan
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                strokeWidth = 6.dp
            )
            Text(
                text = if (useAnimatedPhrases && message == null) currentPhrase else (message ?: currentPhrase),
                style = MaterialTheme.typography.headlineSmall // Increased from bodyLarge (~24sp vs 16sp)
            )
        }
    }
}

/**
 * Inline loading indicator
 */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp)
        )
    }
}
