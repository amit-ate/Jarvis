package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class VoiceSamplesTest {
    @Test
    fun `wav round trip keeps the samples`(@TempDir dir: File) {
        val f = File(dir, "a.wav")
        val s = FloatArray(1600) { (it % 100) / 100f - 0.5f }
        VoiceSampleStore.writeWav(f, s)
        assertEquals(44L + 3200L, f.length())
        assertArrayEquals(s, VoiceSampleStore.readWav(f), 1e-4f)
    }

    @Test
    fun `saved samples are listed with their labels and survive a restart`(@TempDir dir: File) {
        var t = 1000L
        val store = VoiceSampleStore(dir, clock = { t++ })
        store.save(FloatArray(800), "Tell me a joke.", VoiceSampleStore.SOURCE_ENROLL)
        store.save(FloatArray(800), "what time is it", VoiceSampleStore.SOURCE_TURN)
        val again = VoiceSampleStore(dir)
        assertEquals(listOf("Tell me a joke.", "what time is it"), again.list().map { it.text })
        assertEquals(setOf("Tell me a joke."), again.enrolledTexts())
        assertEquals(1, again.count(VoiceSampleStore.SOURCE_TURN))
        assertTrue(again.list().all { File(dir, it.wav).isFile })
    }

    @Test
    fun `empty audio or label is not saved`(@TempDir dir: File) {
        val store = VoiceSampleStore(dir)
        assertNull(store.save(FloatArray(0), "hi", VoiceSampleStore.SOURCE_TURN))
        assertNull(store.save(FloatArray(10), "  ", VoiceSampleStore.SOURCE_TURN))
        assertEquals(0, store.list().size)
    }

    @Test
    fun `old turn recordings are pruned but read-aloud ones are kept`(@TempDir dir: File) {
        var t = 0L
        val store = VoiceSampleStore(dir, maxTurnSamples = 2, clock = { t++ })
        store.save(FloatArray(10), "enrolled", VoiceSampleStore.SOURCE_ENROLL)
        repeat(4) { store.save(FloatArray(10), "turn $it", VoiceSampleStore.SOURCE_TURN) }
        assertEquals(listOf("enrolled", "turn 2", "turn 3"), store.list().map { it.text })
        assertEquals(3, dir.listFiles { f -> f.extension == "wav" }!!.size)
    }
}
