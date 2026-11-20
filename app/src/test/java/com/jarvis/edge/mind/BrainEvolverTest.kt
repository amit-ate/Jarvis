package com.jarvis.edge.mind

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class BrainEvolverTest {

    private fun goodReplyFor(probe: OnDeviceEvalGate.EvalProbe): String = when (probe.id) {
        "echo_photosynthesis" -> "SAY: Plants convert sunlight and water into glucose and oxygen."
        "echo_sky_blue" -> "SAY: Atmosphere molecules scatter blue light more strongly."
        "echo_http" -> "SAY: Hypertext Transfer Protocol."
        "echo_saturn" -> "SAY: Saturn is famous for its icy planetary rings."
        "greet_hello" -> "SAY: I am running smoothly and ready."
        "fact_math" -> "SAY: 4."
        "fact_france" -> "SAY: Paris."
        "fact_week" -> "SAY: 7 days."
        "tool_torch" -> """{"tool":"torch","args":{"state":"on"}}"""
        "tool_web_search" -> """{"tool":"web_search","args":{"query":"Albert Einstein"}}"""
        else -> "SAY: Direct non-echo answer."
    }

    @Test
    fun `overnight window and gating respect charging thermal pause idle and manual override`() {
        var hour = 3
        val evolver = BrainEvolver(clockMs = { 10_000L }, hourOfDayProvider = { hour })

        assertFalse(evolver.isOvernightWindow(0))
        assertTrue(evolver.isOvernightWindow(1))
        assertTrue(evolver.isOvernightWindow(5))
        assertFalse(evolver.isOvernightWindow(6))
        assertFalse(evolver.isOvernightWindow(23))

        val ideal = SelfStateSnapshot(charging = true, healthLevelName = "NORMAL", learningPaused = false)
        assertTrue(evolver.canRunOvernight(ideal, isIdle = true))
        assertFalse(evolver.canRunOvernight(ideal, isIdle = false))
        assertFalse(evolver.canRunOvernight(ideal.copy(charging = false), isIdle = true))
        assertFalse(evolver.canRunOvernight(ideal.copy(healthLevelName = "WARM"), isIdle = true))
        assertFalse(evolver.canRunOvernight(ideal.copy(learningPaused = true), isIdle = true))

        // Outside overnight window
        hour = 14
        assertFalse(evolver.canRunOvernight(ideal, isIdle = true, manualOverride = false))
        // Manual override bypasses charging + hour, but still enforces idle, NORMAL thermal, and not paused
        assertTrue(evolver.canRunOvernight(ideal.copy(charging = false), isIdle = true, manualOverride = true))
        assertFalse(evolver.canRunOvernight(ideal.copy(learningPaused = true), isIdle = true, manualOverride = true))
        assertFalse(evolver.canRunOvernight(ideal.copy(healthLevelName = "HOT"), isIdle = true, manualOverride = true))
    }

    @Test
    fun `buildContrastivePairs combines episodes and corrections and pads between 4 and 8 pairs`() {
        val evolver = BrainEvolver()

        val (minPos, minNeg) = evolver.buildContrastivePairs(emptyList(), emptyList())
        assertTrue(minPos.size in 4..8)
        assertEquals(minPos.size, minNeg.size)

        val episodes = (1..6).map { i ->
            Episode(
                id = i.toLong(),
                timestampMs = i * 1000L,
                liveTranscript = "question $i",
                accurateTranscript = "question $i",
                chosenTranscript = "question $i",
                reply = "direct answer $i",
                echoed = false,
                outcomeOk = true,
            )
        } + listOf(
            Episode(
                id = 99L,
                timestampMs = 99_000L,
                liveTranscript = "echo trap",
                accurateTranscript = "echo trap",
                chosenTranscript = "echo trap",
                reply = "echo trap",
                echoed = true,
                outcomeOk = false,
            )
        )
        val corrections = listOf(
            AsrCorrection(id = 1L, heard = "jar vis", meant = "Jarvis", createdAtMs = 1000L),
            AsrCorrection(id = 2L, heard = "deep seek", meant = "DeepSeek", createdAtMs = 2000L),
            AsrCorrection(id = 3L, heard = "arjun", meant = "Arjun", createdAtMs = 3000L),
        )

        val (pos, neg) = evolver.buildContrastivePairs(episodes, corrections)
        assertTrue(pos.size in 4..8)
        assertEquals(pos.size, neg.size)
        assertTrue(pos.any { "direct answer 1" in it })
        assertTrue(neg.any { "echo trap" in it })
    }

    @Test
    fun `writeJvec writes exact 24-byte little-endian header and float32 payload`(@TempDir tempDir: Path) {
        val evolver = BrainEvolver()
        val outFile = File(tempDir.toFile(), "adapters/test_vec.jvec")
        val nEmbd = 4
        val nLayersMinus1 = 3
        val data = FloatArray(nLayersMinus1 * nEmbd) { idx -> (idx + 1) * 0.25f }

        evolver.writeJvec(
            file = outFile,
            nEmbd = nEmbd,
            nLayersMinus1 = nLayersMinus1,
            ilStart = 1,
            ilEnd = 2,
            data = data,
        )

        assertTrue(outFile.isFile)
        val bytes = outFile.readBytes()
        assertEquals(24 + data.size * 4, bytes.size)

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val magic = ByteArray(4)
        buf.get(magic)
        assertEquals("JVEC", String(magic, Charsets.US_ASCII))
        assertEquals(1, buf.int)
        assertEquals(nEmbd, buf.int)
        assertEquals(nLayersMinus1, buf.int)
        assertEquals(1, buf.int)
        assertEquals(2, buf.int)

        val decoded = FloatArray(data.size) { buf.float }
        assertArrayEquals(data, decoded, 1e-6f)
    }

    @Test
    fun `evolveOnce adopts improved candidate writes jvec and updates ModelVersion`(@TempDir tempDir: Path) {
        val now = 123_456_789L
        val evolver = BrainEvolver(clockMs = { now })
        val store = InMemoryMindStore(clockMs = { now })
        val adaptersDir = tempDir.resolve("adapters").toFile()

        val nEmbd = 8
        val nLayer = 6 // ilStart = 2, ilEnd = 4, payload = 5 * 8 = 40 floats
        var activeVec: FloatArray? = null
        val appliedCalls = mutableListOf<Triple<FloatArray?, Int, Int>>()

        val result = evolver.evolveOnce(
            store = store,
            adaptersDir = adaptersDir,
            baseModelName = "qwen2.5-0.5b-instruct-q4_0",
            nEmbd = nEmbd,
            nLayer = nLayer,
            previousAdapterData = null,
            previousIlStart = 1,
            previousIlEnd = 1,
            trainVectorFn = { pos, neg, ilStart, ilEnd ->
                assertEquals(pos.size, neg.size)
                assertEquals(2, ilStart)
                assertEquals(4, ilEnd)
                FloatArray((nLayer - 1) * nEmbd) { 0.1f }
            },
            applyVectorFn = { data, _, ilStart, ilEnd ->
                activeVec = data
                appliedCalls.add(Triple(data, ilStart, ilEnd))
            },
            evalGate = OnDeviceEvalGate(),
            generateFn = { probe ->
                if (activeVec == null && probe.id == "fact_math") {
                    "SAY: Not sure"
                } else {
                    goodReplyFor(probe)
                }
            },
        )

        assertTrue(result.attempted)
        assertTrue(result.adopted)
        assertNotNull(result.adapterFile)
        assertEquals("brain_v$now.jvec", result.adapterFile!!.name)
        assertTrue(result.adapterFile!!.isFile)
        assertNotNull(result.modelVersion)
        assertTrue(result.modelVersion!!.active)
        assertEquals(1.0f, result.candidateEval!!.evalScore, 1e-5f)
        assertEquals(0.9f, result.baselineEval!!.evalScore, 1e-5f)
        assertNotNull(activeVec)
        assertEquals(1, store.listModelVersions().size)
        assertTrue(store.listModelVersions().first().active)
    }

    @Test
    fun `evolveOnce restores previous adapter and records inactive version on regression`(@TempDir tempDir: Path) {
        val evolver = BrainEvolver(clockMs = { 555_000L })
        val store = InMemoryMindStore(clockMs = { 555_000L })
        val adaptersDir = tempDir.resolve("adapters").toFile()
        val prevVec = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
        var liveVec: FloatArray? = prevVec
        var liveStart = 1
        var liveEnd = 2

        val result = evolver.evolveOnce(
            store = store,
            adaptersDir = adaptersDir,
            baseModelName = "qwen2.5-0.5b",
            nEmbd = 4,
            nLayer = 3, // payload = 2 * 4 = 8 floats
            previousAdapterData = prevVec,
            previousIlStart = 1,
            previousIlEnd = 2,
            trainVectorFn = { _, _, _, _ -> FloatArray(8) { 0.25f } },
            applyVectorFn = { data, _, s, e ->
                liveVec = data
                liveStart = s
                liveEnd = e
            },
            evalGate = OnDeviceEvalGate(),
            generateFn = { probe ->
                // Candidate causes echo regression
                if (liveVec !== prevVec && probe.id == "echo_photosynthesis") {
                    "SAY: Explain photosynthesis briefly"
                } else {
                    goodReplyFor(probe)
                }
            },
        )

        assertTrue(result.attempted)
        assertFalse(result.adopted)
        assertNull(result.adapterFile)
        assertNotNull(result.modelVersion)
        assertFalse(result.modelVersion!!.active)
        assertArrayEquals(prevVec, liveVec)
        assertEquals(1, liveStart)
        assertEquals(2, liveEnd)
        assertFalse(adaptersDir.exists() && adaptersDir.listFiles()?.isNotEmpty() == true)
    }

    @Test
    fun `evolveOnce rejects invalid candidate vector or interruption and restores cleanly`(@TempDir tempDir: Path) {
        val evolver = BrainEvolver(clockMs = { 777_000L })
        val store = InMemoryMindStore(clockMs = { 777_000L })
        val adaptersDir = tempDir.resolve("adapters").toFile()

        // Zero-norm vector rejected
        var restoredNull = false
        val zeroResult = evolver.evolveOnce(
            store = store,
            adaptersDir = adaptersDir,
            baseModelName = "qwen2.5-0.5b",
            nEmbd = 4,
            nLayer = 4,
            previousAdapterData = null,
            previousIlStart = 1,
            previousIlEnd = 1,
            trainVectorFn = { _, _, _, _ -> FloatArray(12) { 0f } },
            applyVectorFn = { data, _, _, _ -> if (data == null) restoredNull = true },
            evalGate = OnDeviceEvalGate(),
            generateFn = ::goodReplyFor,
        )
        assertTrue(zeroResult.attempted)
        assertFalse(zeroResult.adopted)
        assertTrue(restoredNull)
        assertNotNull(zeroResult.modelVersion)
        assertFalse(zeroResult.modelVersion!!.active)

        // Interrupted before candidate adoption restores previous adapter
        var interruptedNow = false
        var liveApplied: FloatArray? = null
        val prev = FloatArray(12) { 1f }
        val interruptResult = evolver.evolveOnce(
            store = store,
            adaptersDir = adaptersDir,
            baseModelName = "qwen2.5-0.5b",
            nEmbd = 4,
            nLayer = 4,
            previousAdapterData = prev,
            previousIlStart = 1,
            previousIlEnd = 2,
            trainVectorFn = { _, _, _, _ ->
                interruptedNow = true
                FloatArray(12) { 0.3f }
            },
            applyVectorFn = { data, _, _, _ -> liveApplied = data },
            evalGate = OnDeviceEvalGate(),
            generateFn = ::goodReplyFor,
            isInterrupted = { interruptedNow },
        )
        assertTrue(interruptResult.attempted)
        assertFalse(interruptResult.adopted)
        assertArrayEquals(prev, liveApplied)
    }

    @Test
    fun `EmbeddingMigrator re-embeds mismatched facts in place without creating duplicates`() {
        val store = InMemoryMindStore(clockMs = { 90_000L })
        val old1 = VectorMath.simpleHashEmbed("User lives in Springfield", dim = 128)
        val old2 = VectorMath.simpleHashEmbed("User likes espresso", dim = 128)
        val already384 = FloatArray(384) { 0.05f }

        store.learnFact("User lives in Springfield", old1, sourceEpisodeId = 1L, confidence = 0.9f)
        store.learnFact("User likes espresso", old2, sourceEpisodeId = 2L, confidence = 0.8f)
        store.learnFact("User already migrated", already384, sourceEpisodeId = 3L, confidence = 1.0f)

        val migratedCount = EmbeddingMigrator.migrateIfNeeded(
            store = store,
            targetDim = 384,
            embedFn = { text -> VectorMath.simpleHashEmbed(text, dim = 384) },
        )

        assertEquals(2, migratedCount)
        assertEquals(3, store.countActiveFacts())

        val activeFacts = store.listActiveFacts(50)
        assertTrue(activeFacts.all { it.embedding.size == 384 })
        val unchanged = activeFacts.first { it.text == "User already migrated" }
        assertArrayEquals(already384, unchanged.embedding, 1e-6f)

        // Second run should be a no-op
        val secondRun = EmbeddingMigrator.migrateIfNeeded(
            store = store,
            targetDim = 384,
            embedFn = { text -> VectorMath.simpleHashEmbed(text, dim = 384) },
        )
        assertEquals(0, secondRun)
    }
}
