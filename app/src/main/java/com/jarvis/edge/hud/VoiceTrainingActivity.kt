package com.jarvis.edge.hud

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
        val store = JarvisService.voiceSamples()
        val prompts = VoicePrompts.SENTENCES
        setContent {
            val state by JarvisService.state.collectAsState()
            val result by JarvisService.enrollResult.collectAsState()
            var done by remember { mutableStateOf(emptySet<String>()) }
            var turnCount by remember { mutableIntStateOf(0) }
            var index by remember { mutableIntStateOf(0) }
            var status by remember { mutableStateOf("Tap Record, wait for the chime, then read the sentence.") }
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
                    status = "I didn't hear anything. Tap Record and try again."
                }
            }

            Column(
                Modifier.fillMaxSize().background(Color(0xFF020617)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Train my voice", color = Color(0xFF38BDF8), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { finish() }) { Text("Done", color = Color(0xFF94A3B8)) }
                }
                Text(
                    "Recorded ${done.size} of ${prompts.size} sentences · $turnCount request recordings",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                )
                Text(status, color = Color(0xFF64748B), fontSize = 14.sp)
                Text(
                    prompts[index],
                    color = Color(0xFFE2E8F0),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), RoundedCornerShape(14.dp))
                        .padding(20.dp),
                )
                if (prompts[index] in done) {
                    Text("Already recorded. Record again to add another take.", color = Color(0xFF64748B), fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            waiting = true
                            status = "Listening… read it now."
                            JarvisService.requestEnroll(this@VoiceTrainingActivity, prompts[index])
                        },
                        enabled = !waiting && state == AssistantState.IDLE,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0369A1)),
                    ) { Text(if (waiting) "Listening…" else "Record") }
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { index = nextUnrecorded(index); status = "Skipped." }, enabled = !waiting) {
                        Text("Skip", color = Color(0xFF94A3B8))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Also keep recordings of my requests", color = Color(0xFF94A3B8), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Switch(checked = keepTurns, onCheckedChange = {
                        keepTurns = it
                        JarvisService.setSaveVoiceSamplesEnabled(this@VoiceTrainingActivity, it)
                    })
                }
                Text(
                    "Recordings stay on this phone in ${JarvisService.VOICE_SAMPLES_DIR}. To train, connect the phone " +
                        "to the laptop and run tools/voice-train/train.sh; it installs the improved recogniser here.",
                    color = Color(0xFF64748B),
                    fontSize = 12.sp,
                )
            }
        }
    }
}
