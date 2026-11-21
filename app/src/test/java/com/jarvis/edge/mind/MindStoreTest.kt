package com.jarvis.edge.mind

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class MindStoreTest {

    @Test
    fun `vector math roundtrip normalization and cosine similarity`() {
        val raw = floatArrayOf(3f, 4f, 0f)
        val norm = VectorMath.l2Normalize(raw)
        assertEquals(0.6f, norm[0], 1e-5f)
        assertEquals(0.8f, norm[1], 1e-5f)

        val bytes = VectorMath.toBytes(norm)
        val decoded = VectorMath.fromBytes(bytes)
        assertEquals(norm.size, decoded.size)
        for (i in norm.indices) {
            assertEquals(norm[i], decoded[i], 1e-6f)
        }

        val v1 = VectorMath.simpleHashEmbed("My dog's name is Bruno")
        val v2 = VectorMath.simpleHashEmbed("What is my dog Bruno's name")
        val v3 = VectorMath.simpleHashEmbed("Quantum chromodynamics lattice gauge")
        assertTrue(VectorMath.cosineSimilarity(v1, v2) > VectorMath.cosineSimilarity(v1, v3))
        assertEquals(1.0f, VectorMath.cosineSimilarity(v1, v1), 1e-4f)
    }

    @Test
    fun `every learn method atomically creates journal entry and supports undo`() {
        var now = 1_000_000L
        val store = InMemoryMindStore(clockMs = { now })

        val fact = store.learnFact("My favorite coffee is espresso", VectorMath.simpleHashEmbed("favorite coffee espresso"))
        now += 1000
        val vocab = store.learnVocab("Jarvis", "user")
        now += 1000
        val corr = store.learnCorrection("jar vis", "Jarvis")
        now += 1000
        val sc = store.promoteShortcut("turn on flashlight", "torch", "{\"state\":\"on\"}", VectorMath.simpleHashEmbed("turn on flashlight"))

        assertEquals(1, store.countActiveFacts())
        assertEquals(1, store.countActiveVocab())
        assertEquals(1, store.countActiveCorrections())
        assertEquals(1, store.countActiveShortcuts())
        assertEquals("Jarvis", store.listActiveCorrectionsMap()["jar vis"])

        val journal = store.listJournal()
        assertEquals(4, journal.size)
        assertTrue(journal.any { it.type == "fact" && it.targetId == fact.id })
        assertTrue(journal.any { it.type == "vocab" && it.targetId == vocab.id })
        assertTrue(journal.any { it.type == "correction" && it.targetId == corr.id })
        assertTrue(journal.any { it.type == "shortcut" && it.targetId == sc.id })

        // Undo last learning (shortcut)
        val undone = store.undoLastLearning()
        assertNotNull(undone)
        assertEquals("shortcut", undone!!.type)
        assertEquals(0, store.countActiveShortcuts())
        assertTrue(store.listJournal().any { it.type == "undo" && it.targetId == sc.id })

        // Undo specific journal entry (fact)
        val factJournal = store.listJournal().first { it.type == "fact" && it.targetId == fact.id }
        assertTrue(store.undoJournalEntry(factJournal.id))
        assertEquals(0, store.countActiveFacts())
        assertFalse(store.undoJournalEntry(factJournal.id)) // idempotent
    }

    @Test
    fun `semantic retrieval matches facts episodes shortcuts and forgetting works`() {
        val store = InMemoryMindStore(clockMs = { 5_000L })
        store.learnFact("User lives in Springfield", VectorMath.simpleHashEmbed("User lives in Springfield"))
        store.learnFact("User drinks black coffee", VectorMath.simpleHashEmbed("User drinks black coffee"))
        store.addEpisode(
            Episode(
                timestampMs = 5_000L,
                liveTranscript = "what is the capital of france",
                accurateTranscript = "what is the capital of france",
                chosenTranscript = "what is the capital of france",
                reply = "The capital of France is Paris.",
            )
        )
        store.promoteShortcut(
            "turn on the torch",
            "torch",
            "{\"state\":\"on\"}",
            VectorMath.simpleHashEmbed("turn on the torch"),
        )

        val facts = store.retrieveRelevantFacts(VectorMath.simpleHashEmbed("Where do I live in Springfield?"))
        assertTrue(facts.isNotEmpty())
        assertEquals("User lives in Springfield", facts.first().text)

        val ep = store.retrieveRelevantEpisode(VectorMath.simpleHashEmbed("capital of france paris"))
        assertNotNull(ep)
        assertTrue(ep!!.reply.contains("Paris"))

        val sc = store.matchShortcut(VectorMath.simpleHashEmbed("turn on the torch"), minSim = 0.85f)
        assertNotNull(sc)
        assertEquals("torch", sc!!.toolName)

        val forgotten = store.forgetMatchingFact("coffee", VectorMath.simpleHashEmbed("coffee"))
        assertNotNull(forgotten)
        assertEquals(1, store.countActiveFacts())
    }

    @Test
    fun `model version tracking rollback and legacy notes migration`() {
        var now = 10_000L
        val store = InMemoryMindStore(clockMs = { now })

        val v1 = store.recordModelVersion("qwen2.5-0.5b", null, evalScore = 0.78f, echoRate = 0.04f, toolAccuracy = 0.85f, active = true)
        now += 1000
        val v2 = store.recordModelVersion("qwen2.5-0.5b", "/sdcard/Jarvis/adapters/v2.gguf", evalScore = 0.86f, echoRate = 0.02f, toolAccuracy = 0.92f, active = true)

        assertTrue(store.listModelVersions().first { it.id == v2.id }.active)
        assertFalse(store.listModelVersions().first { it.id == v1.id }.active)

        val rolled = store.rollbackModelVersion()
        assertNotNull(rolled)
        assertEquals(v1.id, rolled!!.id)
        assertTrue(store.listModelVersions().first { it.id == v1.id }.active)

        val migrated = store.migrateLegacyNotes(
            listOf(
                "WiFi password is example-pass" to 100L,
                "WiFi password is example-pass" to 101L, // duplicate ignored
                "Mom birthday is March 3" to 200L,
            )
        )
        assertEquals(2, migrated)
        assertEquals(2, store.countActiveFacts())
        assertEquals(2, store.listJournal().count { it.type == "migration" })
    }

    @Test
    fun `exportToJson and learningPaused persist cleanly`(@TempDir tempDir: Path) {
        val store = InMemoryMindStore(clockMs = { 42_000L })
        store.setLearningPaused(true)
        assertTrue(store.isLearningPaused())
        store.learnFact("Project Jarvis runs on Snapdragon 636", VectorMath.simpleHashEmbed("Project Jarvis Snapdragon 636"))
        store.learnVocab("Snapdragon", "reflection")

        val outFile = tempDir.resolve("nested/mind_export.json").toFile()
        assertTrue(store.exportToJson(outFile))
        assertTrue(outFile.isFile)

        val parsed = JSONObject(outFile.readText())
        assertTrue(parsed.getBoolean("learning_paused"))
        assertEquals(1, parsed.getJSONArray("facts").length())
        assertEquals(1, parsed.getJSONArray("vocabulary").length())
        assertEquals(2, parsed.getJSONArray("journal").length())
    }

    @Test
    fun `hotwords writer tokenizes BPE phrases and writes unique lines atomically`(@TempDir tempDir: Path) {
        val sampleTokens = """
            <blk> 0
            ▁THE 4
            ▁JAR 10
            VIS 11
            ▁AM 12
            Y 13
            ▁ 14
            A 15
            B 16
            C 17
        """.trimIndent().lineSequence()

        val writer = HotwordsWriter(HotwordsWriter.parseTokens(sampleTokens))
        assertEquals("▁JAR VIS", writer.tokenizePhrase("Jarvis"))
        assertEquals("▁AM Y", writer.tokenizePhrase("Amy"))
        assertNull(writer.tokenizePhrase("12345")) // digits not in token table

        val outFile = File(tempDir.toFile(), "hotwords.txt")
        val count = writer.writeHotwords(listOf("Jarvis", "jarvis", "Amy", "12345"), outFile)
        assertEquals(2, count)
        val lines = outFile.readLines().filter { it.isNotBlank() }
        assertEquals(listOf("▁JAR VIS", "▁AM Y"), lines)
    }

    @Test
    fun `self model captures safely on JVM and prompt line stays under 120 chars`() {
        val store = InMemoryMindStore(clockMs = { 1000L })
        store.learnFact("Test fact", VectorMath.simpleHashEmbed("Test fact"))
        val model = SelfModel(context = null, store = store)
        val snap = model.capture()

        assertEquals(1, snap.factCount)
        val promptLine = SelfModel.toPromptLine(snap)
        assertTrue(promptLine.length <= 120, "Prompt line too long: ${promptLine.length}")
        val spoken = SelfModel.toSpokenSummary(snap)
        assertTrue(spoken.contains("1 facts"))
    }

    @Test
    fun `mind tools handle remember recall forget remind and mind_status`() = runBlocking {
        var now = 1_700_000_000_000L
        val store = InMemoryMindStore(clockMs = { now })
        val embed: (String) -> FloatArray = { VectorMath.simpleHashEmbed(it) }
        var mutedMs = 0L
        val snapProvider = {
            SelfStateSnapshot(batteryPct = 85, batteryTempC = 32f, availRamMb = 2100, totalRamMb = 5800, factCount = store.countActiveFacts())
        }

        val remember = MindRememberTool(store, embed)
        val recall = MindRecallTool(store, embed)
        val forget = MindForgetTool(store, embed)
        val remind = RemindTool(store, clockMs = { now })
        val status = MindStatusTool(store, snapProvider, onMuteRequested = { mutedMs = it }, clockMs = { now })

        assertTrue(remember.run(mapOf("text" to "My spare key is in the blue drawer")).ok)
        val recalled = recall.run(mapOf("query" to "blue drawer spare key"))
        assertTrue(recalled.ok)
        assertTrue(recalled.spoken.contains("blue drawer"))

        assertTrue(remind.run(mapOf("when" to "10 minutes", "task" to "check the oven")).ok)
        assertEquals(0, store.dueReminders(now).size)
        assertEquals(1, store.dueReminders(now + 10 * 60_000L).size)

        assertTrue(remind.run(mapOf("when" to "18:30", "task" to "take vitamins")).ok)
        assertEquals(2, store.dueReminders(now + 24 * 3600_000L).size)

        assertTrue(status.run(mapOf("action" to "learned_today")).spoken.contains("learned"))
        assertTrue(status.run(mapOf("action" to "know_about_me")).spoken.contains("blue drawer"))
        assertTrue(status.run(mapOf("action" to "self_status")).spoken.contains("85 percent"))
        assertTrue(status.run(mapOf("action" to "pause_learning")).ok)
        assertTrue(store.isLearningPaused())
        assertTrue(status.run(mapOf("action" to "resume_learning")).ok)
        assertFalse(store.isLearningPaused())
        assertTrue(status.run(mapOf("action" to "be_quiet")).ok)
        assertEquals(3600_000L, mutedMs)

        // Forget target
        assertTrue(forget.run(mapOf("target" to "blue drawer")).ok)
        assertEquals(0, store.countActiveFacts())

        // Rollback brain
        store.recordModelVersion("base-v1", null, 0.80f, 0.03f, 0.85f, active = true)
        store.recordModelVersion("adapter-v2", "/tmp/v2.gguf", 0.75f, 0.09f, 0.70f, active = true)
        val rb = status.run(mapOf("action" to "rollback_brain"))
        assertTrue(rb.ok)
        assertTrue(rb.spoken.contains("base-v1"))
    }

    @Test
    fun `begun but not completed episode is listed as interrupted`() {
        val store = InMemoryMindStore()
        val id = store.beginEpisode(5_000L, "live", "accurate", "chosen")
        val interrupted = store.listInterruptedEpisodes(sinceMs = 0L)
        assertEquals(1, interrupted.size)
        assertEquals(id, interrupted[0].id)
        assertEquals("", interrupted[0].reply)
        assertFalse(interrupted[0].outcomeOk)
        assertFalse(interrupted[0].reflected)
        assertTrue(store.listInterruptedEpisodes(sinceMs = 5_001L).isEmpty())
    }

    @Test
    fun `completed episode keeps reply and outcome`() {
        val store = InMemoryMindStore()
        val id = store.beginEpisode(5_000L, "live", "accurate", "chosen")
        store.completeEpisode(id, "It's sunny", null, null, echoed = false)
        val ep = store.listRecentEpisodes(10).single { it.id == id }
        assertEquals("It's sunny", ep.reply)
        assertTrue(ep.outcomeOk)
        assertTrue(ep.completed)
        assertTrue(store.listInterruptedEpisodes(sinceMs = 0L).isEmpty())
    }

    @Test
    fun `begun episode is not completed and addEpisode is completed`() {
        val store = InMemoryMindStore()
        val id = store.beginEpisode(5_000L, "live", "accurate", "chosen")
        assertFalse(store.listRecentEpisodes(10).single { it.id == id }.completed)
        val added = store.addEpisode(
            Episode(timestampMs = 6_000L, liveTranscript = "a", accurateTranscript = "a", chosenTranscript = "a", reply = "b"),
        )
        assertTrue(store.listRecentEpisodes(10).single { it.id == added.id }.completed)
    }

    @Test
    fun `tool-only turn with blank reply is completed and not listed as interrupted`() {
        val store = InMemoryMindStore()
        val id = store.beginEpisode(5_000L, "live", "accurate", "chosen")
        store.completeEpisode(id, "", "torch", "{}", echoed = false)
        val ep = store.listRecentEpisodes(10).single { it.id == id }
        assertTrue(ep.completed)
        assertTrue(ep.outcomeOk)
        assertTrue(store.listInterruptedEpisodes(sinceMs = 0L).isEmpty())
    }

    @Test
    fun `completeEpisode honours explicit outcomeOk false for stopped turns`() {
        val store = InMemoryMindStore()
        val id = store.beginEpisode(5_000L, "live", "accurate", "chosen")
        store.completeEpisode(id, "", null, null, echoed = false, outcomeOk = false)
        val ep = store.listRecentEpisodes(10).single { it.id == id }
        assertTrue(ep.completed)
        assertFalse(ep.outcomeOk)
        assertFalse(ep.echoed)
        assertTrue(store.listInterruptedEpisodes(sinceMs = 0L).isEmpty())
    }
}
