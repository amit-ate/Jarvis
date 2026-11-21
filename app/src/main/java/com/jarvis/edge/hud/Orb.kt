package com.jarvis.edge.hud

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.edge.core.AssistantState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

private const val BARS = 72

/**
 * The reactor: a glowing core inside a tick ring, a rotating segmented ring and a ring of
 * bars that follows the microphone. Thinking adds two orbiting comets; a web search turns it
 * amber with a radar sweep and orbiting dots.
 */
@Composable
fun JarvisOrb(
    state: AssistantState,
    micLevel: Float,
    modifier: Modifier = Modifier,
    searching: Boolean = false,
    size: Dp = 280.dp,
) {
    val color by animateColorAsState(Jx.stateColor(state, searching), tween(450), label = "orbColor")
    val energy by animateFloatAsState(
        when {
            searching -> 0.8f
            state == AssistantState.IDLE -> 0.25f
            state == AssistantState.ERROR -> 0.4f
            else -> 1f
        },
        tween(600), label = "energy",
    )

    // Rolling microphone history, newest first; drives the bar ring.
    val history = remember { FloatArray(BARS / 2) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(micLevel) {
        System.arraycopy(history, 0, history, 1, history.size - 1)
        history[0] = micLevel.coerceIn(0f, 1f)
        tick++
    }

    val t = rememberInfiniteTransition(label = "reactor")
    val slow by t.animateFloat(0f, 360f, infiniteRepeatable(tween(48_000, easing = LinearEasing)), label = "slow")
    val mid by t.animateFloat(360f, 0f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "mid")
    val fast by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "fast")
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2_600), RepeatMode.Reverse), label = "breath")

    Canvas(modifier.size(size)) {
        @Suppress("UNUSED_EXPRESSION") tick
        val c = center
        val r = this.size.minDimension / 2f
        val core = r * (0.34f + 0.02f * breath + 0.05f * (if (state == AssistantState.LISTENING || state == AssistantState.SPEAKING) history[0] else 0f))

        // Halo
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.28f + 0.12f * energy), color.copy(alpha = 0.06f), Color.Transparent), c, r),
            r, c,
        )

        // Outer tick ring, slowly turning.
        rotate(slow, c) {
            for (k in 0 until 120) {
                val a = (k * 3f) * (PI / 180f).toFloat()
                val major = k % 10 == 0
                val r1 = r * 0.93f
                val r2 = r * (if (major) 0.86f else 0.895f)
                drawLine(
                    color.copy(alpha = if (major) 0.75f else 0.28f),
                    Offset(c.x + cos(a) * r1, c.y + sin(a) * r1),
                    Offset(c.x + cos(a) * r2, c.y + sin(a) * r2),
                    (if (major) 2f else 1f) * density,
                )
            }
        }

        // Segmented ring turning the other way.
        val segR = r * 0.79f
        rotate(mid, c) {
            for (k in 0 until 3) {
                drawArc(
                    color.copy(alpha = 0.55f),
                    startAngle = k * 120f, sweepAngle = 78f, useCenter = false,
                    topLeft = Offset(c.x - segR, c.y - segR), size = Size(segR * 2, segR * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Butt),
                )
                drawArc(
                    color.copy(alpha = 0.25f),
                    startAngle = k * 120f + 84f, sweepAngle = 8f, useCenter = false,
                    topLeft = Offset(c.x - segR, c.y - segR), size = Size(segR * 2, segR * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Butt),
                )
            }
        }

        // Voice bars: mirrored so the ring is symmetric left/right.
        val barBase = r * 0.50f
        val barMax = r * 0.20f
        val live = state == AssistantState.LISTENING || state == AssistantState.SPEAKING
        for (k in 0 until BARS) {
            val half = if (k < BARS / 2) k else BARS - 1 - k
            val v = if (live) history[half] else 0f
            val idle = 0.06f + 0.05f * sin((k * 0.5f) + breath * 6.28f)
            val len = barMax * (idle + v * 0.95f).coerceIn(0.03f, 1f)
            val a = (k * 360f / BARS - 90f) * (PI / 180f).toFloat()
            drawLine(
                color.copy(alpha = 0.35f + 0.6f * (if (live) v else 0.2f)),
                Offset(c.x + cos(a) * barBase, c.y + sin(a) * barBase),
                Offset(c.x + cos(a) * (barBase + len), c.y + sin(a) * (barBase + len)),
                2.2f * density, StrokeCap.Round,
            )
        }

        // Core: a lit sphere with a thin inner ring.
        drawCircle(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.95f), color.copy(alpha = 0.95f), color.copy(alpha = 0.35f), Color.Transparent),
                Offset(c.x - core * 0.25f, c.y - core * 0.3f), core * 1.25f,
            ),
            core, c,
        )
        drawCircle(color.copy(alpha = 0.8f), core * 1.12f, c, style = Stroke(1.2f * density))

        when {
            searching -> drawSearch(c, r, color, fast)
            state == AssistantState.THINKING -> drawComets(c, r * 0.64f, color, fast)
            state == AssistantState.ERROR -> {
                val er = r * 0.64f
                for (k in 0 until 6) {
                    drawArc(
                        color.copy(alpha = 0.5f + 0.4f * breath), k * 60f, 40f, false,
                        Offset(c.x - er, c.y - er), Size(er * 2, er * 2), style = Stroke(3.dp.toPx()),
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawComets(c: Offset, rr: Float, color: Color, spin: Float) {
    for (k in 0 until 2) {
        val start = spin * 1f + k * 180f
        for (j in 0 until 8) {
            drawArc(
                Color.White.copy(alpha = 0.85f * (1f - j / 8f)),
                startAngle = start - j * 9f, sweepAngle = 9f, useCenter = false,
                topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2),
                style = Stroke((3.5f - j * 0.3f) * density, cap = StrokeCap.Round),
            )
        }
    }
}

private fun DrawScope.drawSearch(c: Offset, r: Float, color: Color, spin: Float) {
    // Radar wedge sweeping round the bar ring.
    rotate(spin, c) {
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, Color.Transparent, color.copy(alpha = 0.45f)), c),
            startAngle = 270f, sweepAngle = 90f, useCenter = true,
            topLeft = Offset(c.x - r * 0.74f, c.y - r * 0.74f), size = Size(r * 1.48f, r * 1.48f),
        )
    }
    // Three dots orbiting the other way, like results coming in.
    val orbit = r * 0.7f
    for (k in 0 until 3) {
        val a = Math.toRadians((-spin * 0.6f + k * 120f).toDouble())
        drawCircle(Color.White, 3.5f * density, Offset(c.x + (orbit * cos(a)).toFloat(), c.y + (orbit * sin(a)).toFloat()))
    }
}
