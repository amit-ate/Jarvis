package com.jarvis.edge.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechSegmenterTest {

    private class ScriptedVad(private val script: (Int) -> Boolean) : Vad {
        var idx = 0
        override fun isSpeech(frame: FloatArray): Boolean = script(idx++)
    }

    private fun frame(v: Float = 0.1f) = FloatArray(512) { v }

    @Test
    fun `returns Done after 700 ms of trailing silence`() {
        val vad = ScriptedVad { it < 30 }
        val seg = SpeechSegmenter(vad)
        repeat(30) {
            assertEquals(SpeechSegmenter.Result.Pending, seg.accept(frame()))
        }
        repeat(21) {
            assertEquals(SpeechSegmenter.Result.Pending, seg.accept(frame()))
        }
        val res = seg.accept(frame())
        assertTrue(res is SpeechSegmenter.Result.Done)
        assertEquals(30 * 512, (res as SpeechSegmenter.Result.Done).samples.size)
    }

    @Test
    fun `returns NoSpeech when nothing is said for 4 seconds`() {
        val vad = ScriptedVad { false }
        val seg = SpeechSegmenter(vad)
        repeat(124) {
            assertEquals(SpeechSegmenter.Result.Pending, seg.accept(frame()))
        }
        assertEquals(SpeechSegmenter.Result.NoSpeech, seg.accept(frame()))
    }

    @Test
    fun `caps a never-ending utterance at 15 seconds`() {
        val vad = ScriptedVad { true }
        val seg = SpeechSegmenter(vad)
        var doneAt = -1
        for (i in 1..600) {
            val res = seg.accept(frame())
            if (res is SpeechSegmenter.Result.Done) {
                doneAt = i
                break
            }
        }
        assertEquals(469, doneAt)
    }

    @Test
    fun `shortTail ends after 450 ms of silence`() {
        val vad = ScriptedVad { it < 30 }
        val seg = SpeechSegmenter(vad)
        repeat(30) {
            assertEquals(SpeechSegmenter.Result.Pending, seg.accept(frame(), shortTail = false))
        }
        repeat(14) {
            assertEquals(SpeechSegmenter.Result.Pending, seg.accept(frame(), shortTail = true))
        }
        val res = seg.accept(frame(), shortTail = true)
        assertTrue(res is SpeechSegmenter.Result.Done)
        assertEquals(30 * 512, (res as SpeechSegmenter.Result.Done).samples.size)
    }

    @Test
    fun `gated wake word never calls inner during silence`() {
        val vad = ScriptedVad { false }
        var innerCalls = 0
        val inner = object : WakeWord {
            override fun accept(frame: FloatArray): Boolean {
                innerCalls++
                return false
            }
            override fun reset() {}
        }
        val gated = GatedWakeWord(vad, inner)
        repeat(300) {
            assertFalse(gated.accept(frame()))
        }
        assertEquals(0, innerCalls)
    }

    @Test
    fun `gated wake word replays pre-roll so the phrase onset is not lost`() {
        val vad = ScriptedVad { it >= 50 }
        val received = mutableListOf<Float>()
        val inner = object : WakeWord {
            override fun accept(frame: FloatArray): Boolean {
                received.add(frame[0])
                return false
            }
            override fun reset() {}
        }
        val gated = GatedWakeWord(vad, inner, preRollMs = 500, hangoverMs = 1000)
        repeat(50) { i ->
            assertFalse(gated.accept(FloatArray(512) { i.toFloat() }))
        }
        assertEquals(0, received.size)

        repeat(40) { i ->
            assertFalse(gated.accept(FloatArray(512) { (50 + i).toFloat() }))
        }
        // 16 pre-roll frames (indices 34..49) + 40 speech frames (indices 50..89)
        assertEquals(56, received.size)
        assertEquals(34f, received[0])
        assertEquals(49f, received[15])
        assertEquals(50f, received[16])
    }
}
