package com.jarvis.edge.hud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.core.ChatMessage
import com.jarvis.edge.service.JarvisService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Chat history (spoken and typed) plus text mode: type a request and read the answer. */
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val log = JarvisService.chatLog(this)
        val prefs = getSharedPreferences("jarvis_chat_ui", MODE_PRIVATE)
        setContent {
            val messages by log.messages.collectAsState()
            val state by JarvisService.state.collectAsState()
            val searching by JarvisService.searching.collectAsState()
            var input by remember { mutableStateOf("") }
            var speakReplies by remember { mutableStateOf(prefs.getBoolean("speak", false)) }
            var followUp by remember { mutableStateOf(JarvisService.followUpEnabled(this@ChatActivity)) }
            val listState = rememberLazyListState()
            LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
                if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
            }
            fun send() {
                val text = input.trim()
                if (text.isEmpty()) return
                JarvisService.submitText(this@ChatActivity, text, speakReplies)
                input = ""
            }
            Column(
                Modifier.fillMaxSize().background(Color(0xFF020617)).imePadding().padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Chat", color = Color(0xFF38BDF8), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (searching) "searching the web…" else statusLabel(state),
                        color = if (searching) Color(0xFFF59E0B) else Color(0xFF64748B),
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = {
                        startActivity(android.content.Intent(this@ChatActivity, VoiceTrainingActivity::class.java))
                    }) { Text("Train voice", color = Color(0xFF38BDF8)) }
                    TextButton(onClick = { log.clear() }) { Text("Clear", color = Color(0xFF94A3B8)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Speak replies", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    Switch(checked = speakReplies, onCheckedChange = {
                        speakReplies = it
                        prefs.edit().putBoolean("speak", it).apply()
                    })
                    Spacer(Modifier.width(16.dp))
                    Text("Voice follow-up", color = Color(0xFF94A3B8), fontSize = 13.sp)
                    Switch(checked = followUp, onCheckedChange = {
                        followUp = it
                        JarvisService.setFollowUpEnabled(this@ChatActivity, it)
                    })
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Text(
                                "No conversation yet. Say \"Jarvis\" or type below.",
                                color = Color(0xFF64748B),
                                modifier = Modifier.padding(top = 24.dp),
                            )
                        }
                    }
                    items(messages, key = { it.id }) { Bubble(it) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Type a message", color = Color(0xFF64748B)) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color(0xFFE2E8F0),
                            unfocusedTextColor = Color(0xFFE2E8F0),
                            focusedBorderColor = Color(0xFF38BDF8),
                            unfocusedBorderColor = Color(0xFF334155),
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { send() },
                        enabled = input.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0369A1)),
                    ) { Text("Send") }
                }
            }
        }
    }

    private fun statusLabel(state: AssistantState): String = when (state) {
        AssistantState.THINKING -> "thinking…"
        AssistantState.LISTENING -> "listening…"
        AssistantState.SPEAKING -> "speaking…"
        else -> ""
    }
}

private val timeFmt = SimpleDateFormat("h:mm a", Locale.US)

@androidx.compose.runtime.Composable
private fun Bubble(m: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(
                    if (m.fromUser) Color(0xFF0C4A6E) else Color(0xFF1E293B),
                    RoundedCornerShape(14.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(m.text, color = Color(0xFFE2E8F0), fontSize = 16.sp)
            Text(
                (if (m.fromUser) (if (m.typed) "typed · " else "said · ") else "Jarvis · ") + timeFmt.format(Date(m.timeMs)),
                color = Color(0xFF64748B),
                fontSize = 11.sp,
            )
        }
    }
}
