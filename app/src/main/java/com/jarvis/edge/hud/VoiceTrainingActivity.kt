package com.jarvis.edge.hud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.core.VoicePrompts
import com.jarvis.edge.core.VoiceSampleStore
import com.jarvis.edge.service.JarvisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Voice training: the user reads short sentences aloud after a chime; each recording is saved with its exact text
 * in /sdcard/Jarvis/voice_samples so the recogniser can be fine-tuned on their voice (tools/voice-train).
 */
class VoiceTrainingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.parseColor("#060B16")
        window.navigationBarColor = android.graphics.Color.parseColor("#02040A")
        val store = JarvisService.voiceSamples()
        val prompts = VoicePrompts.SENTENCES
        setContent {
            val state by JarvisService.state.collectAsState()
            val micLevel by JarvisService.micLevel.collectAsState()
            val result by JarvisService.enrollResult.collectAsState()
            var done by remember { mutableStateOf(emptySet<String>()) }
            var turnCount by remember { mutableIntStateOf(0) }
            var index by remember { mutableIntStateOf(0) }
            var status by remember { mutableStateOf("Tap the mic, wait for the chime, then read the sentence.") }
            var waiting by remember { mutableStateOf(false) }
            var keepTurns by remember { mutableStateOf(JarvisService.saveVoiceSamplesEnabled(this@VoiceTrainingActivity)) }
            val startSeq = remember { result?.seq ?: 0L }

            suspend fun refresh() {
                val (enrolled, turns) = withContext(Dispatchers.IO) {
                    store.enrolledTexts() to store.count(VoiceSampleStore.SOURCE_TURN)
                }
                done = enrolled
                turnCount = turns
            }
            fun nextUnrecorded(from: Int): Int {
                for (k in 1..prompts.size) {
                    val i = (from + k) % prompts.size
                    if (prompts[i] !in done) return i
                }
                return (from + 1) % prompts.size
            }

            LaunchedEffect(Unit) {
                refresh()
                if (prompts[index] in done) index = nextUnrecorded(index)
            }
            LaunchedEffect(result?.seq) {
                val r = result ?: return@LaunchedEffect
                if (r.seq <= startSeq) return@LaunchedEffect
                waiting = false
                refresh()
                if (r.saved) {
                    status = "Saved. Next one:"
                    index = nextUnrecorded(index)
                } else {
                    status = "I didn't hear anything. Tap the mic and try again."
                }
            }

            JarvisTheme {
            Column(
                Modifier.fillMaxSize().background(Jx.backdrop).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ScreenHeader(
                    title = "Train my voice",
                    subtitle = "${done.size} of ${prompts.size} sentences · $turnCount requests kept",
                    onBack = { finish() },
                )
                // Progress bar.
                val frac = if (prompts.isEmpty()) 0f else done.size.toFloat() / prompts.size
                Box(Modifier.fillMaxWidth().height(6.dp).background(Jx.PanelHi, RoundedCornerShape(3.dp))) {
                    Box(
                        Modifier.fillMaxWidth(frac).height(6.dp)
                            .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Jx.Blue, Jx.Mint)), RoundedCornerShape(3.dp)),
                    )
                }
                Spacer(Modifier.height(6.dp))
                GlassPanel(Modifier.fillMaxWidth(), accent = if (waiting) Jx.Mint else Jx.Cyan, padding = androidx.compose.foundation.layout.PaddingValues(22.dp)) {
                    HudLabel("sentence ${index + 1}" + if (prompts[index] in done) " · recorded" else "", color = if (prompts[index] in done) Jx.Mint else Jx.TextFaint, size = 10)
                    Spacer(Modifier.height(10.dp))
                    Text(prompts[index], color = Jx.Text, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
                }
                Text(status, color = if (waiting) Jx.Mint else Jx.TextDim, fontSize = 14.sp)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val canRecord = !waiting && state == AssistantState.IDLE
                        if (waiting) {
                            JarvisOrb(state = AssistantState.LISTENING, micLevel = micLevel, size = 150.dp)
                        } else {
                            RingButton(
                                onClick = {
                                    if (!canRecord) return@RingButton
                                    waiting = true
                                    status = "Listening… read it now."
                                    JarvisService.requestEnroll(this@VoiceTrainingActivity, prompts[index])
                                },
                                size = 88.dp, color = if (canRecord) Jx.Mint else Jx.TextFaint, filled = canRecord,
                            ) { Glyph(GlyphKind.MIC, Color.White, 36.dp) }
                            Spacer(Modifier.height(8.dp))
                            HudLabel(if (canRecord) "tap to record" else "jarvis is busy", color = Jx.TextDim, size = 10)
                        }
                        Spacer(Modifier.height(10.dp))
                        PillButton("Skip  ›", onClick = { index = nextUnrecorded(index); status = "Skipped." }, enabled = !waiting)
                    }
                }
                Spacer(Modifier.weight(1f))
                GlassPanel(Modifier.fillMaxWidth(), brackets = false, padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Also keep recordings of my requests", color = Jx.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Switch(checked = keepTurns, onCheckedChange = {
                            keepTurns = it
                            JarvisService.setSaveVoiceSamplesEnabled(this@VoiceTrainingActivity, it)
                        }, colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Jx.Blue, checkedThumbColor = Color.White))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Recordings stay on this phone in ${JarvisService.VOICE_SAMPLES_DIR}. To train, connect the phone " +
                            "to the laptop and run tools/voice-train/train.sh; it installs the improved recogniser here.",
                        color = Jx.TextFaint,
                        fontSize = 12.sp,
                    )
                }
            }
            }
        }
    }
}
