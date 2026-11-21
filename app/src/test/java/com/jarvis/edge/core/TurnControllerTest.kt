package com.jarvis.edge.core

import com.jarvis.edge.audio.LiveTranscriber
import com.jarvis.edge.audio.Speaker
import com.jarvis.edge.audio.SpeechSegmenter
import com.jarvis.edge.audio.Transcriber
import com.jarvis.edge.audio.Vad
import com.jarvis.edge.brain.Brain
import com.jarvis.edge.brain.FastIntents
import com.jarvis.edge.brain.GenStats
import com.jarvis.edge.brain.LlmEngine
import com.jarvis.edge.brain.PromptBuilder
import com.jarvis.edge.tools.Tool
import com.jarvis.edge.tools.ToolRegistry
import com.jarvis.edge.tools.ToolResult
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class TurnControllerTest {

    private class ScriptedVad(private val speechCount: Int) : Vad {
        private var count = 0
        override fun isSpeech(frame: FloatArray): Boolean {
            count++
            return count <= speechCount
        }
        override fun reset() {
            count = 0
        }
    }

    private class FakeLive(private val reply: String) : LiveTranscriber {
        override fun accept(frame: FloatArray): String = reply
        override fun finish(): String = reply
        override fun reset() = Unit
    }

    private class QueueLive(private val replies: ArrayDeque<String>) : LiveTranscriber {
        private var current = ""
        override fun accept(frame: FloatArray): String = current
        override fun finish(): String {
            current = if (replies.isEmpty()) "" else replies.removeFirst()
            return current
        }
        override fun reset() {
            current = ""
        }
    }

    private class FakeSpeaker(
        private val stopOnSentence: String? = null,
    ) : Speaker {
        val spoken = mutableListOf<String>()
        override var isStopped: Boolean = false

        override suspend fun speak(sentence: String) {
            spoken.add(sentence)
            if (sentence == stopOnSentence) {
                stop()
            }
        }

        override fun stop() {
            isStopped = true
        }
    }

    private fun dummyBrain(llmReply: String = "SAY: Default answer."): Brain {
        val tools = listOf(object : Tool {
            override val name = "get_time"
            override val description = "Get time"
            override val params: Map<String, List<String>?> = emptyMap()
            override suspend fun run(args: Map<String, String>) = ToolResult("It's noon.", ok = true)
        })
        val fakeLlm = object : LlmEngine {
            override val isLoaded: Boolean = true
            override fun load(modelFile: File, contextTokens: Int) = Unit
            override fun generate(
                prompt: String,
                grammar: String?,
                maxTokens: Int,
                onToken: (String) -> Boolean,
            ): GenStats {
                onToken(llmReply)
                return GenStats(20, 10, 5, 50, 15f)
            }
            override fun unload() = Unit
        }
        return Brain(FastIntents, fakeLlm, PromptBuilder(tools), ToolRegistry(tools))
    }

    private fun dummyFrames(count: Int = 200) = flow {
        repeat(count) { emit(FloatArray(512) { 0.1f }) }
    }

    @Test
    fun `empty transcript says sorry once and skips the brain`() = runBlocking {
        val fakeLlm = object : LlmEngine {
            override val isLoaded = true
            override fun load(modelFile: File, contextTokens: Int) = Unit
            override fun generate(prompt: String, grammar: String?, maxTokens: Int, onToken: (String) -> Boolean): GenStats =
                throw AssertionError("Brain/LLM should not run for empty transcript")
            override fun unload() = Unit
        }
        val brain = Brain(FastIntents, fakeLlm, PromptBuilder(emptyList()), ToolRegistry(emptyList()))
        val speaker = FakeSpeaker()
        val states = mutableListOf<AssistantState>()
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64, noSpeechTimeoutMs = 200),
            live = FakeLive(""),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray) = " "
            },
            brain = brain,
            speaker = speaker,
            setState = { states.add(it) },
        )
        tc.runTurn(dummyFrames())
        assertEquals(listOf("Sorry, I didn't catch that."), speaker.spoken)
        assertEquals(
            listOf(AssistantState.LISTENING, AssistantState.THINKING, AssistantState.SPEAKING, AssistantState.IDLE),
            states,
        )
    }

    @Test
    fun `a transcribed request is handed over with its audio for voice samples`() = runBlocking {
        val kept = mutableListOf<Pair<Int, String>>()
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64, shortTrailingSilenceMs = 32),
            live = FakeLive("what is dist moon"),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray) = "what is the distance to the moon"
            },
            brain = dummyBrain("SAY: About 384 thousand kilometers."),
            speaker = FakeSpeaker(),
            setState = {},
            onUtterance = { samples, text -> kept.add(samples.size to text) },
        )
        tc.runTurn(dummyFrames())
        assertEquals(1, kept.size)
        assertEquals("what is the distance to the moon", kept[0].second)
        assertTrue(kept[0].first > 0)
    }

    @Test
    fun `captureUtterance records speech without answering, and null on silence`() = runBlocking {
        val speaker = FakeSpeaker()
        fun controller(vad: Vad) = TurnController(
            segmenter = SpeechSegmenter(vad, trailingSilenceMs = 64, noSpeechTimeoutMs = 96),
            live = FakeLive(""),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray): String = throw AssertionError("no transcription")
            },
            brain = dummyBrain(),
            speaker = speaker,
            setState = {},
        )
        val audio = controller(ScriptedVad(4)).captureUtterance(dummyFrames())
        assertTrue(audio != null && audio.isNotEmpty())
        assertEquals(null, controller(ScriptedVad(0)).captureUtterance(dummyFrames()))
        assertTrue(speaker.spoken.isEmpty())
    }

    @Test
    fun `NoSpeech returns to idle silently`() = runBlocking {
        val speaker = FakeSpeaker()
        val states = mutableListOf<AssistantState>()
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(0), trailingSilenceMs = 64, noSpeechTimeoutMs = 96),
            live = FakeLive(""),
            accurate = null,
            brain = dummyBrain(),
            speaker = speaker,
            setState = { states.add(it) },
        )
        tc.runTurn(dummyFrames())
        assertTrue(speaker.spoken.isEmpty())
        assertEquals(listOf(AssistantState.LISTENING, AssistantState.IDLE), states)
    }

    @Test
    fun `fast intent in the live transcript skips the accurate transcriber`() = runBlocking {
        val speaker = FakeSpeaker()
        val accurate = object : Transcriber {
            override fun transcribe(samples: FloatArray): String =
                throw AssertionError("Accurate transcriber must be skipped for fast intent")
        }
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64, shortTrailingSilenceMs = 32),
            live = FakeLive("what time is it"),
            accurate = accurate,
            brain = dummyBrain(),
            speaker = speaker,
            setState = {},
        )
        tc.runTurn(dummyFrames())
        assertEquals(listOf("It's noon."), speaker.spoken)
    }

    @Test
    fun `non-command speech is re-transcribed by the accurate transcriber`() = runBlocking {
        var accurateCalled = false
        val speaker = FakeSpeaker()
        val accurate = object : Transcriber {
            override fun transcribe(samples: FloatArray): String {
                accurateCalled = true
                return "what is the distance to the moon"
            }
        }
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64, shortTrailingSilenceMs = 32),
            live = FakeLive("what is dist moon"),
            accurate = accurate,
            brain = dummyBrain("SAY: About 384 thousand kilometers."),
            speaker = speaker,
            setState = {},
        )
        tc.runTurn(dummyFrames())
        assertTrue(accurateCalled)
        assertEquals(listOf("About 384 thousand kilometers."), speaker.spoken)
    }

    @Test
    fun `states go LISTENING, THINKING, SPEAKING, IDLE in order`() = runBlocking {
        val states = mutableListOf<AssistantState>()
        val speaker = FakeSpeaker()
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64),
            live = FakeLive("explaingravity"),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray) = "explain gravity"
            },
            brain = dummyBrain("SAY: Gravity pulls mass together. It keeps planets in orbit."),
            speaker = speaker,
            setState = { states.add(it) },
        )
        tc.runTurn(dummyFrames())
        assertEquals(
            listOf(AssistantState.LISTENING, AssistantState.THINKING, AssistantState.SPEAKING, AssistantState.IDLE),
            states,
        )
    }

    @Test
    fun `speaker stop during speaking ends the turn and returns to idle`() = runBlocking {
        val states = mutableListOf<AssistantState>()
        val speaker = FakeSpeaker(stopOnSentence = "First sentence.")
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64),
            live = FakeLive("talk"),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray) = "tell me something"
            },
            brain = dummyBrain("SAY: First sentence. Second sentence."),
            speaker = speaker,
            setState = { states.add(it) },
        )
        tc.runTurn(dummyFrames())
        assertEquals(listOf("First sentence."), speaker.spoken)
        assertFalse("Second sentence." in speaker.spoken)
        assertEquals(AssistantState.IDLE, states.last())
    }

    @Test
    fun `strong ASR disagreement asks confirmation and confirms with yes`() = runBlocking {
        val speaker = FakeSpeaker()
        val confirmed = mutableListOf<Pair<String, String>>()
        val vad = ScriptedVad(2)
        val live = QueueLive(ArrayDeque(listOf("tell about alpha beta", "yes")))
        val accurate = object : Transcriber {
            override fun transcribe(samples: FloatArray) = "explain quantum physics"
        }
        val tc = TurnController(
            segmenter = SpeechSegmenter(vad, trailingSilenceMs = 64),
            live = live,
            accurate = accurate,
            brain = dummyBrain("SAY: Quantum physics studies energy and matter at atomic scales."),
            speaker = speaker,
            setState = {},
            onCorrectionConfirmed = { heard, meant -> confirmed.add(heard to meant) },
        )

        tc.runTurn(dummyFrames())
        assertTrue(tc.hasPendingConfirmation)
        assertEquals(listOf("Did you say explain quantum physics?"), speaker.spoken)

        vad.reset()
        tc.runTurn(dummyFrames())
        assertFalse(tc.hasPendingConfirmation)
        assertEquals(listOf("tell about alpha beta" to "explain quantum physics"), confirmed)
        assertEquals(
            listOf(
                "Did you say explain quantum physics?",
                "Quantum physics studies energy and matter at atomic scales.",
            ),
            speaker.spoken,
        )
    }

    @Test
    fun `restored pending confirmation accepts yes`() = runBlocking {
        val speaker = FakeSpeaker()
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64),
            live = QueueLive(ArrayDeque(listOf("yes"))),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray) = "yes"
            },
            brain = dummyBrain("SAY: Timer set."),
            speaker = speaker,
            setState = {},
        )
        tc.restorePending("explain gravy", "explain gravity")
        assertTrue(tc.hasPendingConfirmation)
        assertEquals("explain gravy" to "explain gravity", tc.snapshotPending())
        tc.runTurn(dummyFrames())
        assertFalse(tc.hasPendingConfirmation)
        assertEquals(listOf("Timer set."), speaker.spoken) // candidate ran through the brain
    }

    @Test
    fun `confirmation answered with no clears pending state and asks to repeat`() = runBlocking {
        val speaker = FakeSpeaker()
        val vad = ScriptedVad(2)
        val live = QueueLive(ArrayDeque(listOf("apple orange", "no")))
        val accurate = object : Transcriber {
            override fun transcribe(samples: FloatArray) = "rocket science"
        }
        val tc = TurnController(
            segmenter = SpeechSegmenter(vad, trailingSilenceMs = 64),
            live = live,
            accurate = accurate,
            brain = dummyBrain("SAY: Should not be spoken."),
            speaker = speaker,
            setState = {},
        )

        tc.runTurn(dummyFrames())
        assertTrue(tc.hasPendingConfirmation)
        vad.reset()
        tc.runTurn(dummyFrames())
        assertFalse(tc.hasPendingConfirmation)
        assertEquals(
            listOf("Did you say rocket science?", TurnController.REPEAT_MSG),
            speaker.spoken,
        )
    }

    @Test
    fun `respond callbacks skip sorry and confirmation prompts`() = runBlocking {
        val started = mutableListOf<String>()
        val sorry = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64, noSpeechTimeoutMs = 200),
            live = FakeLive(""),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray) = " "
            },
            brain = dummyBrain(),
            speaker = FakeSpeaker(),
            setState = {},
            onRespondStart = { started.add(it) },
        )
        sorry.runTurn(dummyFrames())
        val confirm = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64),
            live = QueueLive(ArrayDeque(listOf("tell about alpha beta"))),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray) = "explain quantum physics"
            },
            brain = dummyBrain(),
            speaker = FakeSpeaker(),
            setState = {},
            onRespondStart = { started.add(it) },
        )
        confirm.runTurn(dummyFrames())
        assertTrue(started.isEmpty())
    }

    @Test
    fun `stopping mid-reply reports what was spoken so far`() = runBlocking {
        val started = mutableListOf<String>()
        val interrupted = mutableListOf<String>()
        val tc = TurnController(
            segmenter = SpeechSegmenter(ScriptedVad(2), trailingSilenceMs = 64),
            live = FakeLive("talk"),
            accurate = object : Transcriber {
                override fun transcribe(samples: FloatArray) = "tell me something"
            },
            brain = dummyBrain("SAY: First sentence. Second sentence."),
            speaker = FakeSpeaker(stopOnSentence = "First sentence."),
            setState = {},
            onRespondStart = { started.add(it) },
            onRespondInterrupted = { interrupted.add(it) },
        )
        tc.runTurn(dummyFrames())
        assertEquals(listOf("tell me something"), started)
        assertEquals(listOf("First sentence."), interrupted.map { it.trim() })
    }
}
