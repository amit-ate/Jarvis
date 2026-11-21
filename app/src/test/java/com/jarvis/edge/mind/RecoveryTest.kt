package com.jarvis.edge.mind

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RecoveryTest {
    @TempDir lateinit var tmp: File

    private val repo = InMemoryMindStore(clockMs = { 1L })
    private fun recovery(): Recovery {
        val a = File(tmp, "adapters").apply { mkdirs() }
        val k = File(tmp, "kv").apply { mkdirs() }
        return Recovery(repo, a, k) { 5_000L }
    }

    private fun recoveryJournal() = repo.listJournal(50).filter { it.type == "recovery" }

    @Test
    fun `first run is not unclean`() {
        assertFalse(recovery().onStart().unclean)
    }

    @Test
    fun `missing clean flag after start is unclean`() {
        val r = recovery()
        r.onStart()
        assertTrue(r.onStart().unclean)
        assertEquals(1, recoveryJournal().size)
    }

    @Test
    fun `clean stop then start is clean`() {
        val r = recovery()
        r.onStart()
        r.onCleanStop()
        assertFalse(r.onStart().unclean)
        assertEquals(0, recoveryJournal().size)
    }

    @Test
    fun `corrupted working state is journaled and cleared`() {
        repo.setMeta("working_state", "{not json")
        recovery().onStart()
        assertEquals("", repo.getMeta("working_state"))
        assertEquals("Discarded a corrupted working-memory snapshot.", recoveryJournal().single().summary)
    }

    @Test
    fun `unclean start journals restored text only when working state was restored`() {
        val state = WorkingState(
            savedAtMs = 1_000L, history = emptyList(), lastTurnMs = 0L, pendingHeard = null,
            pendingCandidate = null, initiativeMutedUntilMs = 0L, openEpisodeId = null,
        )
        repo.setMeta("working_state", WorkingStateCodec.encode(state))
        val r = recovery()
        r.onStart()
        r.onStart()
        assertTrue(
            recoveryJournal().single().summary.matches(
                Regex("I was shut down unexpectedly at \\d\\d:\\d\\d; restored my conversation and learning progress\\."),
            ),
        )
    }

    @Test
    fun `unclean start without working state journals learning-safe text`() {
        val r = recovery()
        r.onStart()
        r.onStart()
        assertTrue(
            recoveryJournal().single().summary.matches(
                Regex("I was shut down unexpectedly at \\d\\d:\\d\\d; my learning is safe but I couldn't restore the conversation\\."),
            ),
        )
    }

    @Test
    fun `orphan cleanup spares experts and referenced versions`() {
        val r = recovery()
        val dir = File(tmp, "adapters")
        File(dir, "brain_v1.jvec").writeText("x")
        File(dir, "brain_v2.jvec").writeText("x")
        File(dir, "chat.jvec").writeText("x")
        File(dir, "brain_v3.jvec.tmp").writeText("x")
        repo.recordModelVersion("base", File(dir, "brain_v1.jvec").absolutePath, 0.9f, 0f, 0.9f)
        val report = r.onStart()
        assertEquals(1, report.orphansRemoved)
        assertEquals(1, report.tempsRemoved)
        assertTrue(File(dir, "brain_v1.jvec").exists())
        assertTrue(File(dir, "chat.jvec").exists())
        assertFalse(File(dir, "brain_v2.jvec").exists())
    }
}
