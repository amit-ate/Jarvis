package com.jarvis.edge.hud

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.core.CrashInfo
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Date
import java.util.Locale

/** Things that need the user's attention, most serious first. Each one has its own fix. */
sealed class HudAlert(val color: Color, val title: String, val body: String, val action: String?) {
    class MicDenied : HudAlert(Jx.Red, "Microphone is off", "Jarvis can't hear you without microphone permission.", "Allow")
    class Error(msg: String) : HudAlert(Jx.Red, "Something went wrong", msg, "Restart")
    class SlowStart : HudAlert(Jx.Amber, "Still starting…", "This is taking longer than usual.", "Restart")
    class Degraded(msg: String) : HudAlert(Jx.Amber, "Running in safe mode", msg, null)
    class Hot(level: String) : HudAlert(Jx.Amber, "Phone is ${level.lowercase()}", "Animations paused and heavy work slowed to cool down.", null)
    class Crashed(c: CrashInfo) : HudAlert(
        Jx.Amber, "Recovered from a crash",
        "At ${SimpleDateFormat("h:mm a, d MMM", Locale.US).format(Date(c.timeMs))}: ${c.summary}", "Dismiss",
    )
}

fun isDegradedStatus(msg: String): Boolean =
    msg.contains("basic commands only") || msg.contains("Low memory") || msg.contains("failed", ignoreCase = true)

fun greetingFor(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Late night"
}

/** The always-on home screen: clock and status on top, the reactor in the middle, the conversation below, a dock at the bottom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeHud(
    state: AssistantState,
    searching: Boolean,
    micLevel: Float,
    transcript: String,
    reply: String,
    status: String,
    clockText: String,
    expert: String,
    health: String,
    factCount: Int,
    battery: Int = 0,
    night: Boolean = false,
    calm: Boolean = false,
    alert: HudAlert? = null,
    onAlertAction: (HudAlert) -> Unit = {},
    onOrbTap: () -> Unit,
    onChat: () -> Unit,
    onMind: () -> Unit,
    onSetup: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val accent = Jx.stateColor(state, searching)
    val booting = status.startsWith("Starting")

    // Debounced tap: a double tap must not wake and then immediately stop Jarvis.
    var lastTap by remember { mutableLongStateOf(0L) }
    val tap: () -> Unit = {
        val now = System.currentTimeMillis()
        if (now - lastTap > 700) {
            lastTap = now
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onOrbTap()
        }
    }

    // Burn-in guard: while idle, nudge everything by a few pixels every minute.
    var shift by remember { mutableStateOf(0 to 0) }
    LaunchedEffect(state == AssistantState.IDLE) {
        if (state != AssistantState.IDLE) { shift = 0 to 0; return@LaunchedEffect }
        var k = 0
        while (true) {
            delay(60_000)
            k++
            shift = (listOf(0, 3, 0, -3)[k % 4]) to (listOf(0, 0, 3, 0, -3)[k % 5])
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            // Night mode: everything turns deep red, which is kind to dark-adapted eyes.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (night) drawRect(Color(0xFFFF2A2A), blendMode = BlendMode.Multiply)
            },
    ) {
        Canvas(Modifier.fillMaxSize()) { drawHudBackdrop(accent) }

        Column(
            Modifier.fillMaxSize().offset(shift.first.dp, shift.second.dp).padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                val (time, date) = clockText.split('|').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                Column(Modifier.weight(1f)) {
                    HudLabel(greetingFor(LocalTime.now().hour), color = Jx.Cyan.copy(alpha = 0.8f), size = 10)
                    Text(time, style = TextStyle(color = Jx.Text, fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp))
                    HudLabel(date, color = Jx.TextDim, size = 11)
                }
                RingButton(onClick = onSetup, size = 40.dp, color = Jx.TextDim) { Glyph(GlyphKind.GEAR, Jx.TextDim, 20.dp) }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("ON-DEVICE", Jx.Mint)
                StatusChip(health, if (health.equals("NORMAL", true)) Jx.Cyan else Jx.Amber)
                if (battery > 0) StatusChip("$battery%", if (battery < 20) Jx.Red else Jx.TextDim, dot = false)
                if (night) StatusChip("night", Jx.TextDim, dot = false)
            }

            AnimatedVisibility(alert != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                val a = alert ?: return@AnimatedVisibility
                GlassPanel(Modifier.fillMaxWidth().padding(top = 12.dp), accent = a.color, brackets = false, padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.title, color = a.color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(a.body, color = Jx.TextDim, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (a.action != null) {
                            Spacer(Modifier.width(8.dp))
                            PillButton(a.action, onClick = { onAlertAction(a) }, color = a.color, selected = true)
                        }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val interaction = remember { MutableInteractionSource() }
                    JarvisOrb(
                        state = state, micLevel = micLevel, searching = searching, size = 290.dp, calm = calm,
                        modifier = Modifier.combinedClickable(interactionSource = interaction, indication = null, onClick = tap, onLongClick = onSetup),
                    )
                    Spacer(Modifier.height(6.dp))
                    if (booting) {
                        BootLine()
                    } else {
                        AnimatedContent(Jx.stateLabel(state, searching), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stateLabel") { label ->
                            HudLabel(label, color = accent, size = 13)
                        }
                        Spacer(Modifier.height(4.dp))
                        HudLabel(
                            when (state) {
                                AssistantState.IDLE -> "say \"Jarvis\" or tap the core"
                                AssistantState.SPEAKING -> "tap the core to stop"
                                AssistantState.LISTENING -> "go ahead, I'm listening"
                                else -> " "
                            },
                            color = Jx.TextFaint, size = 9,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = transcript.isNotBlank() || reply.isNotBlank(),
                enter = fadeIn() + slideInVertically { it / 3 },
                exit = fadeOut(),
            ) {
                GlassPanel(Modifier.fillMaxWidth().padding(bottom = 14.dp), accent = accent) {
                    if (transcript.isNotBlank()) {
                        HudLabel("you", color = Jx.TextFaint, size = 9)
                        Spacer(Modifier.height(3.dp))
                        Text(transcript, color = Jx.TextDim, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                    }
                    if (reply.isNotBlank()) {
                        HudLabel("jarvis", color = accent, size = 9)
                        Spacer(Modifier.height(3.dp))
                        Box(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState())) {
                            Text(reply, color = Jx.Text, fontSize = 18.sp, lineHeight = 25.sp)
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                DockItem("Chat", GlyphKind.CHAT, onChat)
                RingButton(onClick = tap, size = 66.dp, color = accent, filled = state != AssistantState.IDLE) {
                    Glyph(if (state == AssistantState.SPEAKING) GlyphKind.STOP else GlyphKind.MIC, if (state == AssistantState.IDLE) accent else Color.White, 28.dp)
                }
                DockItem(if (factCount > 0) "Mind · $factCount" else "Mind", GlyphKind.MIND, onMind)
            }
        }
    }
}

/** Startup readout: a cycling list of what is being brought online. */
@Composable
private fun BootLine() {
    val steps = listOf("speech recogniser", "wake word", "language model", "memory", "voice")
    val t = rememberInfiniteTransition(label = "boot")
    val i by t.animateFloat(0f, steps.size.toFloat(), infiniteRepeatable(tween(4_000), RepeatMode.Restart), label = "step")
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    HudLabel("initialising", color = Jx.Cyan, size = 13)
    Spacer(Modifier.height(4.dp))
    HudLabel("loading ${steps[i.toInt().coerceIn(0, steps.size - 1)]} · ${seconds}s", color = Jx.TextFaint, size = 9)
}

@Composable
private fun DockItem(label: String, kind: GlyphKind, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        RingButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }, size = 48.dp) { Glyph(kind, Jx.Cyan, 22.dp) }
        Spacer(Modifier.height(5.dp))
        HudLabel(label, color = Jx.TextDim, size = 9)
    }
}
