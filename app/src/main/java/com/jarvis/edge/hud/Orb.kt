package com.jarvis.edge.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.jarvis.edge.core.AssistantState

fun hudBrightness(lux: Float, state: AssistantState): Float {
    if (state != AssistantState.IDLE && state != AssistantState.ERROR) {
        return when {
            lux <= 5f -> 0.25f
            lux >= 300f -> 0.65f
            else -> (0.25f + ((lux - 5f) / 295f) * 0.40f).coerceIn(0.25f, 0.65f)
        }
    }
    return when {
        lux <= 5f -> 0.02f
        lux >= 300f -> 0.35f
        else -> (0.02f + ((lux - 5f) / 295f) * 0.33f).coerceIn(0.02f, 0.35f)
    }
}

@Composable
fun JarvisOrb(
    state: AssistantState,
    micLevel: Float,
    modifier: Modifier = Modifier,
    /** A web lookup is running: amber orb with a radar sweep and orbiting dots. */
    searching: Boolean = false,
) {
    val transition = rememberInfiniteTransition(label = "jarvis_orb")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spin",
    )

    Canvas(modifier = modifier.size(200.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = size.minDimension * 0.32f
        val clampedMic = micLevel.coerceIn(0f, 1f)

        val (coreColor, radiusFactor) = if (searching) Color(0xFFF59E0B) to (0.92f + (pulse - 0.88f) * 0.5f) else when (state) {
            AssistantState.IDLE -> Color(0xFF0EA5E9) to (0.90f + (pulse - 0.88f) * 0.35f)
            AssistantState.LISTENING -> Color(0xFF22D3EE) to (0.95f + clampedMic * 0.45f)
            AssistantState.THINKING -> Color(0xFF818CF8) to 0.96f
            AssistantState.SPEAKING -> Color(0xFF34D399) to (0.95f * pulse + clampedMic * 0.2f)
            AssistantState.ERROR -> Color(0xFFEF4444) to 1.0f
        }

        val radius = baseRadius * radiusFactor
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(coreColor.copy(alpha = 0.9f), coreColor.copy(alpha = 0.15f), Color.Transparent),
                center = center,
                radius = radius * 1.45f,
            ),
            radius = radius * 1.45f,
            center = center,
        )
        drawCircle(
            color = coreColor,
            radius = radius,
            center = center,
        )

        if (searching) {
            val ringRadius = radius * 1.3f
            // Radar sweep: a fading trail of arcs behind the leading edge.
            for (k in 0 until 6) {
                drawArc(
                    color = Color(0xFFFDE68A).copy(alpha = 0.75f * (1f - k / 6f)),
                    startAngle = spin * 1.5f - k * 14f,
                    sweepAngle = 14f,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2f, ringRadius * 2f),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // Three dots orbiting the other way, like results coming in.
            val orbit = radius * 1.6f
            for (k in 0 until 3) {
                val a = Math.toRadians((-spin + k * 120f).toDouble())
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = 4.dp.toPx() * (0.8f + 0.4f * (pulse - 0.88f) / 0.2f),
                    center = Offset(center.x + (orbit * kotlin.math.cos(a)).toFloat(), center.y + (orbit * kotlin.math.sin(a)).toFloat()),
                )
            }
        } else if (state == AssistantState.THINKING) {
            val ringRadius = radius * 1.25f
            drawArc(
                color = Color.White.copy(alpha = 0.85f),
                startAngle = spin,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                size = Size(ringRadius * 2f, ringRadius * 2f),
                style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round),
            )
        } else if (state == AssistantState.ERROR) {
            drawCircle(
                color = Color(0xFFFCA5A5),
                radius = radius * 1.18f,
                center = center,
                style = Stroke(width = 5.dp.toPx()),
            )
        }
    }
}
