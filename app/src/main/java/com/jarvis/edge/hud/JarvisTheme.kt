package com.jarvis.edge.hud

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.edge.core.AssistantState

/** One palette and a few building blocks shared by every Jarvis screen. */
object Jx {
    val Void = Color(0xFF02040A)
    val Deep = Color(0xFF060B16)
    val Panel = Color(0xFF0B1324)
    val PanelHi = Color(0xFF111C33)
    val Line = Color(0xFF1C2A44)
    val LineHi = Color(0xFF2A3D60)

    val Text = Color(0xFFE6EEF9)
    val TextDim = Color(0xFF8DA0BC)
    val TextFaint = Color(0xFF52627C)

    val Cyan = Color(0xFF38D5FF)
    val Blue = Color(0xFF2E7BFF)
    val Violet = Color(0xFF8B7CFF)
    val Mint = Color(0xFF3EF0B0)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF5C6C)

    val Mono = FontFamily.Monospace

    fun stateColor(state: AssistantState, searching: Boolean = false): Color = when {
        searching -> Amber
        else -> when (state) {
            AssistantState.IDLE -> Cyan
            AssistantState.LISTENING -> Mint
            AssistantState.THINKING -> Violet
            AssistantState.SPEAKING -> Blue
            AssistantState.ERROR -> Red
        }
    }

    fun stateLabel(state: AssistantState, searching: Boolean = false): String = when {
        searching -> "SEARCHING THE WEB"
        else -> when (state) {
            AssistantState.IDLE -> "STANDING BY"
            AssistantState.LISTENING -> "LISTENING"
            AssistantState.THINKING -> "THINKING"
            AssistantState.SPEAKING -> "SPEAKING"
            AssistantState.ERROR -> "ATTENTION NEEDED"
        }
    }

    val backdrop: Brush = Brush.verticalGradient(listOf(Deep, Void))

    val colorScheme = darkColorScheme(
        primary = Cyan,
        onPrimary = Void,
        secondary = Mint,
        tertiary = Amber,
        error = Red,
        background = Void,
        surface = Panel,
        surfaceVariant = PanelHi,
        onBackground = Text,
        onSurface = Text,
        onSurfaceVariant = TextDim,
        outline = LineHi,
    )
}

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Jx.colorScheme, content = content)
}

/** Small spaced-out caps, the HUD's label voice. */
@Composable
fun HudLabel(text: String, color: Color = Jx.TextFaint, size: Int = 11, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size.sp,
            fontFamily = Jx.Mono,
            fontWeight = FontWeight.Medium,
            letterSpacing = (size * 0.18f).sp,
        ),
    )
}

/** Translucent panel with a hairline border and corner brackets. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    accent: Color = Jx.Cyan,
    corner: Dp = 16.dp,
    brackets: Boolean = true,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Column(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Jx.PanelHi.copy(alpha = 0.78f), Jx.Panel.copy(alpha = 0.62f))))
            .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Jx.Line.copy(alpha = 0.5f)))), shape)
            .drawBehind { if (brackets) drawBrackets(accent.copy(alpha = 0.7f), 10.dp.toPx(), corner.toPx() * 0.45f) }
            .padding(padding),
        content = content,
    )
}

private fun DrawScope.drawBrackets(color: Color, len: Float, inset: Float) {
    val w = 1.5.dp.toPx()
    val i = inset
    // top-left
    drawLine(color, Offset(i, i), Offset(i + len, i), w, StrokeCap.Round)
    drawLine(color, Offset(i, i), Offset(i, i + len), w, StrokeCap.Round)
    // bottom-right
    drawLine(color, Offset(size.width - i, size.height - i), Offset(size.width - i - len, size.height - i), w, StrokeCap.Round)
    drawLine(color, Offset(size.width - i, size.height - i), Offset(size.width - i, size.height - i - len), w, StrokeCap.Round)
}

/** Pill with a glowing dot, for status and small facts. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = true) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text.uppercase(),
            style = TextStyle(color = color, fontSize = 10.sp, fontFamily = Jx.Mono, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp),
        )
    }
}

/** Round icon button with a ring, used for navigation. */
@Composable
fun RingButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    color: Color = Jx.Cyan,
    filled: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(
                if (filled) Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.18f)))
                else Brush.radialGradient(listOf(Jx.PanelHi, Jx.Panel)),
            )
            .border(1.dp, color.copy(alpha = if (filled) 0.9f else 0.35f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Jx.Cyan,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    val c = if (enabled) color else Jx.TextFaint
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) c.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, c.copy(alpha = if (selected) 0.8f else 0.3f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) c else if (enabled) Jx.Text else Jx.TextFaint,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Top bar shared by the inner screens: back ring, title, subtitle, trailing slot. */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = Jx.TextDim,
    onBack: (() -> Unit)?,
    trailing: @Composable () -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        if (onBack != null) {
            RingButton(onClick = onBack, size = 40.dp) { Glyph(GlyphKind.BACK, Jx.Text, 18.dp) }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Jx.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
            if (!subtitle.isNullOrBlank()) {
                Spacer(Modifier.size(2.dp))
                HudLabel(subtitle, color = subtitleColor, size = 10)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) { trailing() }
    }
}

enum class GlyphKind { BACK, CHAT, MIND, GEAR, MIC, STOP, SEND, WAVE, TRASH }

/** Line icons drawn on a canvas (no icon library in the app). */
@Composable
fun Glyph(kind: GlyphKind, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val sw = (s * 0.09f).coerceAtLeast(1.5f)
        val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * s, y * s)
        when (kind) {
            GlyphKind.BACK -> {
                val path = Path().apply { moveTo(0.62f * s, 0.2f * s); lineTo(0.32f * s, 0.5f * s); lineTo(0.62f * s, 0.8f * s) }
                drawPath(path, color, style = st)
            }
            GlyphKind.CHAT -> {
                val path = Path().apply {
                    moveTo(0.18f * s, 0.22f * s); lineTo(0.82f * s, 0.22f * s); lineTo(0.82f * s, 0.66f * s)
                    lineTo(0.46f * s, 0.66f * s); lineTo(0.28f * s, 0.82f * s); lineTo(0.30f * s, 0.66f * s)
                    lineTo(0.18f * s, 0.66f * s); close()
                }
                drawPath(path, color, style = st)
                drawLine(color, p(0.32f, 0.38f), p(0.68f, 0.38f), sw * 0.8f, StrokeCap.Round)
                drawLine(color, p(0.32f, 0.5f), p(0.56f, 0.5f), sw * 0.8f, StrokeCap.Round)
            }
            GlyphKind.MIND -> {
                // A node graph: a learning network.
                val nodes = listOf(p(0.5f, 0.18f), p(0.2f, 0.45f), p(0.8f, 0.45f), p(0.32f, 0.8f), p(0.68f, 0.8f), p(0.5f, 0.52f))
                val edges = listOf(0 to 5, 1 to 5, 2 to 5, 3 to 5, 4 to 5, 0 to 1, 0 to 2, 3 to 4)
                for ((a, b) in edges) drawLine(color.copy(alpha = 0.7f), nodes[a], nodes[b], sw * 0.7f)
                for (n in nodes) drawCircle(color, s * 0.075f, n)
            }
            GlyphKind.GEAR -> {
                val c = p(0.5f, 0.5f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val dx = kotlin.math.cos(a).toFloat(); val dy = kotlin.math.sin(a).toFloat()
                    drawLine(color, Offset(c.x + dx * s * 0.28f, c.y + dy * s * 0.28f), Offset(c.x + dx * s * 0.42f, c.y + dy * s * 0.42f), sw * 1.4f, StrokeCap.Round)
                }
                drawCircle(color, s * 0.27f, c, style = st)
                drawCircle(color, s * 0.09f, c, style = st)
            }
            GlyphKind.MIC -> {
                drawRoundRect(color, topLeft = p(0.36f, 0.12f), size = Size(0.28f * s, 0.46f * s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.14f * s), style = st)
                drawArc(color, 0f, 180f, false, topLeft = p(0.22f, 0.3f), size = Size(0.56f * s, 0.44f * s), style = st)
                drawLine(color, p(0.5f, 0.74f), p(0.5f, 0.88f), sw, StrokeCap.Round)
                drawLine(color, p(0.36f, 0.88f), p(0.64f, 0.88f), sw, StrokeCap.Round)
            }
            GlyphKind.STOP -> drawRoundRect(color, topLeft = p(0.28f, 0.28f), size = Size(0.44f * s, 0.44f * s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.08f * s))
            GlyphKind.SEND -> {
                val path = Path().apply { moveTo(0.16f * s, 0.5f * s); lineTo(0.84f * s, 0.2f * s); lineTo(0.62f * s, 0.84f * s); lineTo(0.5f * s, 0.56f * s); close() }
                drawPath(path, color)
            }
            GlyphKind.WAVE -> {
                val hs = listOf(0.2f, 0.45f, 0.7f, 0.45f, 0.2f)
                hs.forEachIndexed { i, h ->
                    val x = (0.18f + i * 0.16f) * s
                    drawLine(color, Offset(x, (0.5f - h / 2) * s), Offset(x, (0.5f + h / 2) * s), sw * 1.2f, StrokeCap.Round)
                }
            }
            GlyphKind.TRASH -> {
                drawLine(color, p(0.2f, 0.28f), p(0.8f, 0.28f), sw, StrokeCap.Round)
                drawLine(color, p(0.4f, 0.18f), p(0.6f, 0.18f), sw, StrokeCap.Round)
                val path = Path().apply { moveTo(0.28f * s, 0.28f * s); lineTo(0.33f * s, 0.84f * s); lineTo(0.67f * s, 0.84f * s); lineTo(0.72f * s, 0.28f * s) }
                drawPath(path, color, style = st)
            }
        }
    }
}

/** Faint dot grid and vignette behind the HUD; static, so it costs nothing per frame. */
fun DrawScope.drawHudBackdrop(tint: Color) {
    drawRect(Jx.backdrop)
    val step = 28.dp.toPx()
    val dot = 0.9.dp.toPx()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            drawCircle(Jx.Line.copy(alpha = 0.55f), dot, Offset(x, y))
            x += step
        }
        y += step
    }
    drawRect(
        Brush.radialGradient(
            listOf(tint.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width / 2, size.height * 0.42f),
            radius = size.minDimension * 0.9f,
        ),
    )
}
