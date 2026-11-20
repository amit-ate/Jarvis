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

    @Test
    fun `ignores brief click shorter than minSpeechFrames and captures subsequent speech`() {
        // Frames 0..1: 2-frame click (< minSpeechFrames=3)
        // Frames 2..23: 22 frames of trailing silence (700ms) -> resets click
        // Frames 24..28: 5 frames of real speech
        // Frames 29..50: 22 frames of trailing silence -> Done
        val vad = ScriptedVad { idx ->
            idx in 0..1 || idx in 24..28
        }
        val seg = SpeechSegmenter(vad, minSpeechFrames = 3)

        var done: SpeechSegmenter.Result.Done? = null
        for (i in 0..60) {
            val res = seg.accept(FloatArray(512) { i.toFloat() })
            if (res is SpeechSegmenter.Result.Done) {
                done = res
                break
            }
            assertFalse(res is SpeechSegmenter.Result.NoSpeech, "Unexpected NoSpeech at frame $i")
        }

        assertTrue(done != null, "Expected Done after real utterance")
        // 16 pre-roll frames (indices 8..23) + 5 speech frames (indices 24..28) = 21 frames
        assertEquals(21 * 512, done!!.samples.size)
        assertEquals(8f, done.samples[0])
        assertEquals(24f, done.samples[16 * 512])
    }

    @Test
    fun `returns NoSpeech when only brief click occurs before timeout`() {
        val vad = ScriptedVad { idx -> idx == 0 }
        val seg = SpeechSegmenter(vad, minSpeechFrames = 3)
        var gotNoSpeechAt = -1
        for (i in 0..130) {
            val res = seg.accept(frame())
            assertFalse(res is SpeechSegmenter.Result.Done, "Click must not emit Done")
            if (res is SpeechSegmenter.Result.NoSpeech) {
                gotNoSpeechAt = i
                break
            }
        }
        assertEquals(125, gotNoSpeechAt)
    }

    @Test
    fun `SimdAudio normalizeUtterance removes DC offset and normalizes peak in JVM`() {
        // Quiet sine-like wave with +0.2 DC offset and +/-0.1 amplitude
        val samples = FloatArray(64) { i ->
            0.2f + if (i % 2 == 0) 0.1f else -0.1f
        }
        SimdAudio.normalizeUtterance(samples, targetPeak = 0.85f, maxGain = 4.0f)

        // Mean should be ~0, peak should be min(0.85/0.1, 4.0) * 0.1 = 0.4
        val mean = samples.average()
        assertEquals(0.0, mean, 1e-4)
        assertEquals(0.4f, samples[0], 1e-4f)
        assertEquals(-0.4f, samples[1], 1e-4f)

        // Sub-threshold noise (peak <= 0.005f) should have DC removed without amplification
        val noise = FloatArray(16) { i -> 0.1f + if (i % 2 == 0) 0.002f else -0.002f }
        SimdAudio.normalizeUtterance(noise)
        assertEquals(0.002f, noise[0], 1e-4f)
        assertEquals(-0.002f, noise[1], 1e-4f)
    }
}
