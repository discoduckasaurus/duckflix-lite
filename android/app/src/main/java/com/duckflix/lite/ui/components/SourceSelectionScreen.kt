package com.duckflix.lite.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.duckflix.lite.utils.LoadingPhraseGenerator
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Unified source selection screen with slot machine animation
 * Shows during both SEARCHING and DOWNLOADING phases
 */
@Composable
fun SourceSelectionScreen(
    message: String,
    showProgress: Boolean = false,
    progress: Int = 0,
    logoUrl: String? = null,
    backdropUrl: String? = null,
    onCancel: () -> Unit,
    onComeBackLater: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    println("[LOGO-DEBUG-SCREEN] SourceSelectionScreen parameters:")
    println("[LOGO-DEBUG-SCREEN]   logoUrl: $logoUrl")
    println("[LOGO-DEBUG-SCREEN]   backdropUrl: $backdropUrl")
    println("[LOGO-DEBUG-SCREEN]   message: $message")

    // Dynamic phrase generator with API-loaded phrases
    val phraseGenerator = remember { LoadingPhraseGenerator() }
    var currentPair by remember { mutableStateOf(phraseGenerator.generatePair()) }
    var isSpinning by remember { mutableStateOf(false) }
    var showPhrase by remember { mutableStateOf(true) }

    // Trigger new spin every 3 seconds
    LaunchedEffect(Unit) {
        while (true) {
            // Show current phrase for 1.5 seconds
            showPhrase = true
            delay(1500)

            // Hide and spin
            showPhrase = false
            isSpinning = true
            delay(800) // Spin duration

            // Generate new phrase pair
            currentPair = phraseGenerator.generatePair()
            isSpinning = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        // Backdrop image (if provided) - slightly brighter so it's visible but still dim
        backdropUrl?.let { url ->
            Image(
                painter = rememberAsyncImagePainter(url),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.2f), // Increased from 0.05f to 0.2f
                contentScale = ContentScale.Crop
            )
        }

        // Semi-opaque black overlay to ensure player doesn't show through and dims the backdrop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
        )

        TVSafeContent {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight() // Let content determine height, don't force fillMaxSize
            ) {
            // Logo (transparent background) - Fixed size for consistency
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp),
                contentAlignment = Alignment.Center
            ) {
                // Logo image from TMDB (no background, just the logo)
                if (logoUrl != null) {
                    Image(
                        painter = rememberAsyncImagePainter(
                            model = logoUrl,
                            onError = { error ->
                                println("[ERROR] Failed to load logo: $logoUrl - ${error.result.throwable.message}")
                            }
                        ),
                        contentDescription = "Content Logo",
                        modifier = Modifier
                            .height(220.dp)
                            .fillMaxWidth(0.85f)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Slot machine animated phrase
            SlotMachinePhrase(
                phraseA = currentPair.first,
                phraseB = currentPair.second,
                isSpinning = isSpinning,
                showPhrase = showPhrase
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Source info message - server provides comprehensive, user-friendly messages
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            // Progress bar (only shown when progress is significant or downloading)
            if (showProgress) {
                Spacer(modifier = Modifier.height(16.dp))

                // Smooth animated progress bar
                val animatedProgress by animateFloatAsState(
                    targetValue = progress / 100f,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    label = "progressAnimation"
                )

                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .width(600.dp)
                        .height(16.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.White.copy(alpha = 0.3f)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Auto-play message
                if (progress < 100) {
                    Text(
                        text = "Playback will begin automatically at 100%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center
                    )

                    // Show different message if stuck at low progress
                    if (progress < 2) {
                        Text(
                            text = "Low seeders detected - this may take several minutes",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFFFB74D), // Orange warning color
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "This may take up to 2 minutes for new content",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = "Starting playback...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action buttons row
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Come back later button (only show during download with callback)
                if (showProgress && progress < 100 && onComeBackLater != null) {
                    Button(
                        onClick = onComeBackLater,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF4CAF50).copy(alpha = 0.9f) // Green
                        )
                    ) {
                        Text("Come Back Later", color = Color.White)
                    }
                }

                // Cancel/Stop button
                Button(
                    onClick = onCancel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showProgress && progress < 100)
                            Color.Red.copy(alpha = 0.9f)
                        else
                            Color.White.copy(alpha = 0.2f)
                    )
                ) {
                    Text(
                        text = if (showProgress && progress < 100) "Stop Download" else "Cancel",
                        color = Color.White
                    )
                }
            }
        }
        }
    }
}

/**
 * Slot machine style animated text with wave effect and gradient
 */
@Composable
private fun SlotMachinePhrase(
    phraseA: String,
    phraseB: String,
    isSpinning: Boolean,
    showPhrase: Boolean
) {
    val density = LocalDensity.current.density

    // Wave animation time
    val infiniteTransition = rememberInfiniteTransition(label = "wave")
    val waveTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveTime"
    )

    // Gradient animation (3-tone rotating)
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradientOffset"
    )

    // Spinning blur effect
    val spinAlpha = if (isSpinning) 0.3f else if (showPhrase) 1f else 0f

    val alpha by animateFloatAsState(
        targetValue = spinAlpha,
        animationSpec = tween(200),
        label = "phraseAlpha"
    )

    // 3-tone gradient colors (can be changed later via admin panel)
    val gradientColors = listOf(
        Color(0xFF00D9FF), // Cyan
        Color(0xFFFF00E5), // Magenta
        Color(0xFFFFE500)  // Yellow
    )

    // Combine both phrases for unified wave animation
    val fullPhrase = "$phraseA $phraseB"

    // Spinning animation - rotate through Y axis for 3D effect
    val spinRotation by animateFloatAsState(
        targetValue = if (isSpinning) 360f * 3f else 0f, // Spin 3 full rotations
        animationSpec = tween(800, easing = FastOutSlowInEasing),
        label = "spinRotation"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(alpha)
            .graphicsLayer {
                // 3D spinning effect
                rotationX = if (isSpinning) (spinRotation % 360f) else 0f
                cameraDistance = 12f * density
            },
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.Center) {
            // Render full phrase character by character with unified wave
            fullPhrase.forEachIndexed { index, char ->
                if (char == ' ') {
                    Spacer(modifier = Modifier.width(8.dp))
                } else {
                    AnimatedCharacter(
                        char = char,
                        index = index,
                        waveTime = waveTime,
                        gradientColors = gradientColors,
                        gradientOffset = gradientOffset,
                        isSpinning = isSpinning
                    )
                }
            }
        }
    }
}

/**
 * Individual animated character with wave and gradient effects
 */
@Composable
private fun AnimatedCharacter(
    char: Char,
    index: Int,
    waveTime: Float,
    gradientColors: List<Color>,
    gradientOffset: Float,
    isSpinning: Boolean
) {
    // Wave offset per character (continuous across full phrase)
    val waveOffset = if (!isSpinning) {
        sin(waveTime + index * 0.3f) * 8f // 8dp wave amplitude
    } else {
        0f
    }

    // Color interpolation for gradient
    val colorIndex = ((gradientOffset + index * 0.1f) % 1f * gradientColors.size).toInt()
    val nextColorIndex = (colorIndex + 1) % gradientColors.size
    val colorFraction = ((gradientOffset + index * 0.1f) % 1f * gradientColors.size) - colorIndex

    val color = lerp(
        gradientColors[colorIndex],
        gradientColors[nextColorIndex],
        colorFraction
    )

    Text(
        text = char.toString(),
        style = MaterialTheme.typography.displayMedium,
        color = color,
        modifier = Modifier
            .graphicsLayer {
                translationY = waveOffset
            }
    )
}

/**
 * Linear interpolation between two colors
 */
private fun lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = start.alpha + (end.alpha - start.alpha) * fraction
    )
}
