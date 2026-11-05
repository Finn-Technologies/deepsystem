package app.finn.deepsystem.ui.live

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.finn.deepsystem.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun BreathingOrb(
    state: LiveEngineState,
    onTapOrb: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reduceMotion = LocalReduceMotion.current

    // Infinite transitions for smooth continuous breathing and rotation
    val transition = rememberInfiniteTransition(label = "OrbMotion")

    // Slow breath phase (4-second cycle)
    val breathScale by if (!reduceMotion) {
        transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "BreathScale"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    // Thinking vortex spin rotation
    val vortexRotation by if (!reduceMotion) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (state is LiveEngineState.Thinking) 2400 else 8000,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "VortexRotation"
        )
    } else {
        remember { mutableStateOf(0f) }
    }

    // Harmonic wave pulse
    val wavePulse by if (!reduceMotion) {
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "WavePulse"
        )
    } else {
        remember { mutableStateOf(0.5f) }
    }

    val stateDescription = when (state) {
        is LiveEngineState.Listening -> "Listening to audio"
        is LiveEngineState.Thinking -> "Thinking"
        is LiveEngineState.Speaking -> "Speaking response"
        is LiveEngineState.Connecting -> "Connecting to live engine"
        is LiveEngineState.Idle -> "Idle"
        is LiveEngineState.Error -> "Error: ${state.message}"
    }

    Box(
        modifier = modifier
            .size(280.dp)
            .clickable(onClick = onTapOrb)
            .semantics {
                role = Role.Button
                contentDescription = "Breathing Orb, state: $stateDescription. Tap to interrupt or toggle."
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(280.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = size.width * 0.28f

            // Dynamic scale reaction based on state and audio level
            val audioBoost = when (state) {
                is LiveEngineState.Listening -> state.audioLevel * 0.25f
                is LiveEngineState.Speaking -> state.audioLevel * 0.35f
                else -> 0f
            }
            val currentRadius = baseRadius * breathScale * (1f + audioBoost)

            // Primary chromatic palette selection per state
            val (haloColor, coreCenterColor, coreOuterColor) = when (state) {
                is LiveEngineState.Listening -> Triple(OrbAmbientListening, FinnCyan, FinnIndigo)
                is LiveEngineState.Thinking -> Triple(OrbAmbientThinking, FinnViolet, FinnIndigoDark)
                is LiveEngineState.Speaking -> Triple(OrbAmbientSpeaking, FinnIndigo, FinnTeal)
                is LiveEngineState.Error -> Triple(OrbAmbientError, ErrorRose, WarningAmber)
                else -> Triple(OrbAmbientListening, FinnIndigo, FinnCyan)
            }

            // Layer 1: Outer Ambient Radiant Halo
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(haloColor, Color.Transparent),
                    center = center,
                    radius = currentRadius * 2.2f
                ),
                radius = currentRadius * 2.2f,
                center = center
            )

            // Layer 2: Outward Undulating Harmonic Rings (Active during Listening / Speaking)
            if (!reduceMotion && (state is LiveEngineState.Listening || state is LiveEngineState.Speaking)) {
                for (i in 0 until 3) {
                    val phaseOffset = (wavePulse + (i * 0.33f)) % 1f
                    val ringRadius = currentRadius + (phaseOffset * currentRadius * 0.9f)
                    val ringAlpha = (1f - phaseOffset).coerceIn(0f, 0.7f)
                    drawCircle(
                        color = coreCenterColor.copy(alpha = ringAlpha * 0.5f),
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = 2.5f.dp.toPx() * (1f - phaseOffset))
                    )
                }
            }

            // Layer 3: Rotated Inner Swirl Vortex
            rotate(degrees = vortexRotation, pivot = center) {
                val numDots = 8
                val orbitRadius = currentRadius * 0.65f
                for (d in 0 until numDots) {
                    val angle = (d * (360f / numDots)) * (Math.PI / 180f).toFloat()
                    val dotX = center.x + (cos(angle) * orbitRadius)
                    val dotY = center.y + (sin(angle) * orbitRadius)
                    val dotColor = if (d % 2 == 0) FinnCyan else FinnViolet
                    drawCircle(
                        color = dotColor.copy(alpha = 0.55f),
                        radius = 4.5f.dp.toPx(),
                        center = Offset(dotX, dotY)
                    )
                }
            }

            // Layer 4: The Core Breathing Orb
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreCenterColor,
                        FinnViolet.copy(alpha = 0.9f),
                        coreOuterColor
                    ),
                    center = center,
                    radius = currentRadius
                ),
                radius = currentRadius,
                center = center
            )

            // Layer 5: Concentric Luminescent Rim Shimmer
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        FinnCyan.copy(alpha = 0.7f),
                        FinnViolet.copy(alpha = 0.3f),
                        FinnTeal.copy(alpha = 0.7f),
                        FinnIndigo.copy(alpha = 0.4f),
                        FinnCyan.copy(alpha = 0.7f)
                    ),
                    center = center
                ),
                radius = currentRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}
