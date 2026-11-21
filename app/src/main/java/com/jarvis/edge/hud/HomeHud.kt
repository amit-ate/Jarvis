package com.jarvis.edge.hud

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.edge.core.AssistantState

/** The always-on home screen: clock and status on top, the reactor in the middle, the conversation below, a dock at the bottom. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeHud(
    state: AssistantState,
    searching: Boolean,
    micLevel: Float,
    transcript: String,
    reply: String,
    clockText: String,
    expert: String,
    health: String,
    factCount: Int,
    battery: Int = 0,
    onOrbTap: () -> Unit,
    onChat: () -> Unit,
    onMind: () -> Unit,
    onSetup: () -> Unit,
) {
    val accent = Jx.stateColor(state, searching)
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) { drawHudBackdrop(accent) }

        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp)) {
            // Top: time and date on the left, system chips on the right.
            Row(verticalAlignment = Alignment.Top) {
                val (time, date) = clockText.split('|').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
                Column(Modifier.weight(1f)) {
                    Text(
                        time,
                        style = TextStyle(color = Jx.Text, fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = 1.sp),
                    )
                    HudLabel(date, color = Jx.TextDim, size = 11)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RingButton(onClick = onSetup, size = 40.dp, color = Jx.TextDim) { Glyph(GlyphKind.GEAR, Jx.TextDim, 20.dp) }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusChip("ON-DEVICE", Jx.Mint)
                StatusChip(health, if (health.equals("NORMAL", true)) Jx.Cyan else Jx.Amber)
                if (battery > 0) StatusChip("$battery%", if (battery < 20) Jx.Red else Jx.TextDim, dot = false)
                StatusChip(expert.substringBefore('+').lowercase(), Jx.Violet, dot = false)
            }

            // Middle: the reactor and the state label.
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val interaction = remember { MutableInteractionSource() }
                    JarvisOrb(
                        state = state, micLevel = micLevel, searching = searching, size = 290.dp,
                        modifier = Modifier.combinedClickable(interactionSource = interaction, indication = null, onClick = onOrbTap, onLongClick = onSetup),
                    )
                    Spacer(Modifier.height(6.dp))
                    AnimatedContent(Jx.stateLabel(state, searching), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stateLabel") { label ->
                        HudLabel(label, color = accent, size = 13)
                    }
                    Spacer(Modifier.height(4.dp))
                    HudLabel(
                        when (state) {
                            AssistantState.IDLE -> "say \"Jarvis\" or tap the core"
                            AssistantState.SPEAKING -> "tap the core to stop"
                            else -> " "
                        },
                        color = Jx.TextFaint, size = 9,
                    )
                }
            }

            // Conversation card.
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

            // Dock.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                DockItem("Chat", GlyphKind.CHAT, onChat)
                RingButton(onClick = onOrbTap, size = 66.dp, color = accent, filled = state != AssistantState.IDLE) {
                    Glyph(if (state == AssistantState.SPEAKING) GlyphKind.STOP else GlyphKind.MIC, if (state == AssistantState.IDLE) accent else Color.White, 28.dp)
                }
                DockItem(if (factCount > 0) "Mind · $factCount" else "Mind", GlyphKind.MIND, onMind)
            }
        }
    }
}

@Composable
private fun DockItem(label: String, kind: GlyphKind, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        RingButton(onClick = onClick, size = 48.dp) { Glyph(kind, Jx.Cyan, 22.dp) }
        Spacer(Modifier.height(5.dp))
        HudLabel(label, color = Jx.TextDim, size = 9)
    }
}
