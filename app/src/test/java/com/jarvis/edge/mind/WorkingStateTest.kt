package com.jarvis.edge.mind

import com.jarvis.edge.brain.Turn
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorkingStateTest {

    private fun sample() = WorkingState(
        savedAtMs = 1_000L,
        history = listOf(Turn("my dog is Rex", "SAY: Nice."), Turn("what's up", "SAY: Not much.")),
        lastTurnMs = 900L,
        pendingHeard = "set timer fifteen",
        pendingCandidate = "set a timer for 15 minutes",
        initiativeMutedUntilMs = 5_000L,
        openEpisodeId = 7L,
    )

    @Test
    fun `codec roundtrip`() {
        val s = sample()
        assertEquals(s, WorkingStateCodec.decode(WorkingStateCodec.encode(s)))
    }

    @Test
    fun `roundtrip with nulls`() {
        val s = sample().copy(pendingHeard = null, pendingCandidate = null, openEpisodeId = null)
        assertEquals(s, WorkingStateCodec.decode(WorkingStateCodec.encode(s)))
    }

    @Test
    fun `garbage json decodes to null`() {
        assertNull(WorkingStateCodec.decode("{not json"))
    }

    @Test
    fun `unknown version decodes to null`() {
        val json = WorkingStateCodec.encode(sample().copy(version = 99))
        assertNull(WorkingStateCodec.decode(json))
    }

    @Test
    fun `store saves and loads through meta`() {
        val store = WorkingStateStore(InMemoryMindStore(clockMs = { 1L }))
        assertNull(store.load())
        store.save(sample())
        assertEquals(sample(), store.load())
    }
}
