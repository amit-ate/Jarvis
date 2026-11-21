package com.jarvis.edge.hud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.core.ChatMessage
import com.jarvis.edge.core.ModelPaths
import com.jarvis.edge.core.Voices
import com.jarvis.edge.service.JarvisService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Chat history (spoken and typed) plus text mode: type a request and read the answer. */
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#060B16")
        window.navigationBarColor = android.graphics.Color.parseColor("#02040A")
        val log = JarvisService.chatLog(this)
        val prefs = getSharedPreferences("jarvis_chat_ui", MODE_PRIVATE)
        setContent {
            JarvisTheme {
                val messages by log.messages.collectAsState()
                val state by JarvisService.state.collectAsState()
                val searching by JarvisService.searching.collectAsState()
                var input by remember { mutableStateOf("") }
                var speakReplies by remember { mutableStateOf(prefs.getBoolean("speak", false)) }
                var followUp by remember { mutableStateOf(JarvisService.followUpEnabled(this@ChatActivity)) }
                val voices = remember { Voices.available(File(ModelPaths.DEFAULT_ROOT)) }
                var voiceId by remember { mutableStateOf(JarvisService.currentVoiceId(this@ChatActivity)) }
                var showOptions by remember { mutableStateOf(false) }
                val listState = rememberLazyListState()
                val busy = state == AssistantState.THINKING || searching
                // The list is laid out bottom-up, so item 0 is the newest and stays in view when the keyboard opens.
                LaunchedEffect(messages.size, messages.lastOrNull()?.text, busy) {
                    if (messages.isNotEmpty() || busy) listState.animateScrollToItem(0)
                }
                fun send(text: String = input) {
                    val t = text.trim()
                    if (t.isEmpty()) return
                    JarvisService.submitText(this@ChatActivity, t, speakReplies)
                    input = ""
                }
                val accent = Jx.stateColor(state, searching)
                Column(
                    Modifier.fillMaxSize().background(Jx.backdrop).imePadding().padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    ScreenHeader(
                        title = "Chat",
                        subtitle = if (searching) "searching the web" else statusLabel(state) ?: "on-device · private",
                        subtitleColor = if (searching || state != AssistantState.IDLE) accent else Jx.TextFaint,
                        onBack = { finish() },
                    ) {
                        RingButton(onClick = { showOptions = !showOptions }, size = 40.dp, color = if (showOptions) Jx.Cyan else Jx.TextDim, filled = showOptions) {
                            Glyph(GlyphKind.GEAR, if (showOptions) Color.White else Jx.TextDim, 18.dp)
                        }
                        RingButton(onClick = { log.clear() }, size = 40.dp, color = Jx.TextDim) { Glyph(GlyphKind.TRASH, Jx.TextDim, 18.dp) }
                    }

                    AnimatedVisibility(showOptions, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        GlassPanel(Modifier.fillMaxWidth().padding(vertical = 6.dp), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                            ToggleRow("Speak replies", "read answers aloud", speakReplies) {
                                speakReplies = it
                                prefs.edit().putBoolean("speak", it).apply()
                            }
                            ToggleRow("Voice follow-up", "keep listening after an answer", followUp) {
                                followUp = it
                                JarvisService.setFollowUpEnabled(this@ChatActivity, it)
                            }
                            if (voices.size > 1) {
                                Spacer(Modifier.height(8.dp))
                                HudLabel("speaking voice", size = 9)
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (v in voices) {
                                        PillButton(v.label, onClick = {
                                            voiceId = v.id
                                            JarvisService.setVoice(this@ChatActivity, v.id)
                                        }, selected = v.id == voiceId)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            PillButton("Train Jarvis on my voice  ›", onClick = {
                                startActivity(android.content.Intent(this@ChatActivity, VoiceTrainingActivity::class.java))
                            }, color = Jx.Mint)
                        }
                    }

                    if (messages.isEmpty() && !busy) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { EmptyChat(onPick = { send(it) }) }
                    } else {
                        LazyColumn(
                            state = listState,
                            reverseLayout = true,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                        ) {
                            if (busy) item(key = "typing") { TypingBubble(searching, accent) }
                            items(messages.asReversed(), key = { it.id }) { Bubble(it) }
                        }
                    }

                    // Composer.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(28.dp))
                            .background(Jx.Panel)
                            .border(1.dp, if (input.isNotBlank()) Jx.Cyan.copy(alpha = 0.6f) else Jx.Line, RoundedCornerShape(28.dp))
                            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            if (input.isEmpty()) Text("Ask Jarvis anything…", color = Jx.TextFaint, fontSize = 16.sp)
                            BasicTextField(
                                value = input,
                                onValueChange = {
                                    // Enter sends (hardware keys and keyboards that ignore the Send action).
                                    if (it.endsWith("\n")) { input = it.trimEnd('\n'); send() } else input = it
                                },
                                textStyle = TextStyle(color = Jx.Text, fontSize = 16.sp),
                                cursorBrush = SolidColor(Jx.Cyan),
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { send() }),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        if (input.isBlank()) {
                            RingButton(onClick = {
                                if (state == AssistantState.SPEAKING) JarvisService.requestStopSpeaking(this@ChatActivity)
                                else JarvisService.requestManualWake(this@ChatActivity)
                            }, size = 44.dp, color = accent) {
                                Glyph(if (state == AssistantState.SPEAKING) GlyphKind.STOP else GlyphKind.MIC, accent, 22.dp)
                            }
                        } else {
                            RingButton(onClick = { send() }, size = 44.dp, color = Jx.Cyan, filled = true) {
                                Glyph(GlyphKind.SEND, Color.White, 20.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun statusLabel(state: AssistantState): String? = when (state) {
        AssistantState.THINKING -> "thinking"
        AssistantState.LISTENING -> "listening"
        AssistantState.SPEAKING -> "speaking"
        else -> null
    }
}

private val SUGGESTIONS = listOf(
    "What's the weather tomorrow?",
    "Set a timer for 5 minutes",
    "Tell me a short joke",
    "What do you know about me?",
)

@Composable
private fun EmptyChat(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        JarvisOrb(state = AssistantState.IDLE, micLevel = 0f, size = 120.dp)
        Spacer(Modifier.height(14.dp))
        Text("How can I help?", color = Jx.Text, fontSize = 22.sp)
        Spacer(Modifier.height(4.dp))
        Text("Everything runs on this phone.", color = Jx.TextDim, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            for (s in SUGGESTIONS) PillButton(s, onClick = { onPick(s) })
        }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Jx.Text, fontSize = 15.sp)
            Text(hint, color = Jx.TextFaint, fontSize = 12.sp)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Jx.Blue,
                uncheckedThumbColor = Jx.TextDim, uncheckedTrackColor = Jx.PanelHi, uncheckedBorderColor = Jx.LineHi,
            ),
        )
    }
}

private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)

@Composable
private fun Bubble(m: ChatMessage) {
    val shape = if (m.fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!m.fromUser) {
            Avatar()
            Spacer(Modifier.width(8.dp))
        }
        Column(
            Modifier
                .widthIn(max = 290.dp)
                .clip(shape)
                .background(
                    if (m.fromUser) Brush.linearGradient(listOf(Color(0xFF1B5FD6), Color(0xFF1593C9)))
                    else Brush.verticalGradient(listOf(Jx.PanelHi, Jx.Panel)),
                )
                .then(if (m.fromUser) Modifier else Modifier.border(1.dp, Jx.Line, shape))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(m.text, color = if (m.fromUser) Color.White else Jx.Text, fontSize = 16.sp, lineHeight = 22.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                (if (m.fromUser) (if (m.typed) "typed · " else "spoken · ") else "") + timeFmt.format(Date(m.timeMs)),
                color = if (m.fromUser) Color.White.copy(alpha = 0.65f) else Jx.TextFaint,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun Avatar(color: Color = Jx.Cyan) {
    Box(
        Modifier.size(28.dp).clip(CircleShape)
            .background(Brush.radialGradient(listOf(Color.White, color, color.copy(alpha = 0.2f))))
            .border(1.dp, color.copy(alpha = 0.6f), CircleShape),
    )
}

@Composable
private fun TypingBubble(searching: Boolean, accent: Color) {
    val t = rememberInfiniteTransition(label = "typing")
    val phase by t.animateFloat(0f, 3f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "phase")
    Row(verticalAlignment = Alignment.Bottom) {
        Avatar(accent)
        Spacer(Modifier.width(8.dp))
        Row(
            Modifier.clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)).background(Jx.Panel)
                .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (k in 0 until 3) {
                val on = phase.toInt() == k
                Box(Modifier.size(7.dp).clip(CircleShape).background(accent.copy(alpha = if (on) 1f else 0.3f)))
                Spacer(Modifier.width(5.dp))
            }
            if (searching) {
                Spacer(Modifier.width(4.dp))
                HudLabel("searching the web", color = accent, size = 9)
            }
        }
    }
}
