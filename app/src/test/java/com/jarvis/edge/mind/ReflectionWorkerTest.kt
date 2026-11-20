package com.jarvis.edge.mind

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ReflectionWorkerTest {

    @Test
    fun `respects canRun learningPaused and interrupted guards`() {
        val store = InMemoryMindStore(clockMs = { 10_000L })
        store.addEpisode(
            Episode(
                timestampMs = 10_000L,
                liveTranscript = "hello",
                accurateTranscript = "hello",
                chosenTranscript = "hello",
                reply = "Hi!",
            )
        )

        val worker = ReflectionWorker(store)
        val r1 = worker.reflectOnce(canRun = false)
        assertEquals(0, r1.episodesProcessed)
        assertEquals(1, store.listUnreflectedEpisodes().size)

        store.setLearningPaused(true)
        val r2 = worker.reflectOnce(canRun = true)
        assertEquals(0, r2.episodesProcessed)
        store.setLearningPaused(false)

        val r3 = worker.reflectOnce(canRun = true, isInterrupted = { true })
        assertTrue(r3.aborted)
        assertEquals(1, store.listUnreflectedEpisodes().size)
    }

    @Test
    fun `learns corrections vocab and regenerates hotwords on explicit correction and repeat`(@TempDir tempDir: Path) {
        val store = InMemoryMindStore(clockMs = { 100_000L })
        // Two repeated utterances within 30s -> first episode marked unsatisfactory
        val ep1 = store.addEpisode(
            Episode(
                timestampMs = 10_000L,
                liveTranscript = "call arjun",
                accurateTranscript = "call origin",
                chosenTranscript = "call origin",
                reply = "Calling origin?",
                outcomeOk = true,
            )
        )
        store.addEpisode(
            Episode(
                timestampMs = 20_000L,
                liveTranscript = "call origin",
                accurateTranscript = "call origin",
                chosenTranscript = "call origin",
                reply = "I didn't find origin.",
                outcomeOk = true,
            )
        )
        // Explicit correction referencing previous utterance
        store.addEpisode(
            Episode(
                timestampMs = 25_000L,
                liveTranscript = "no I said call Arjun",
                accurateTranscript = "no I said call Arjun",
                chosenTranscript = "no I said call Arjun",
                reply = "Got it.",
            )
        )
        // Inline "not X, Y" correction
        store.addEpisode(
            Episode(
                timestampMs = 30_000L,
                liveTranscript = "not deep seek, DeepSeek",
                accurateTranscript = "not deep seek, DeepSeek",
                chosenTranscript = "not deep seek, DeepSeek",
                reply = "Noted.",
            )
        )

        val tokenMap = buildMap {
            put("▁", 1)
            for (c in 'A'..'Z') put(c.toString(), c.code)
        }
        val writer = HotwordsWriter(tokenMap)
        val hotwordsFile = File(tempDir.toFile(), "hotwords.txt")

        val worker = ReflectionWorker(
            store = store,
            hotwordsWriter = writer,
            hotwordsOutFile = hotwordsFile,
            clockMs = { 100_000L },
        )
        val report = worker.reflectOnce()

        assertFalse(report.aborted)
        assertEquals(4, report.episodesProcessed)
        assertEquals(2, report.correctionsLearned)
        assertEquals(2, report.vocabLearned)

        val updatedEp1 = store.listRecentEpisodes().first { it.id == ep1.id }
        assertFalse(updatedEp1.outcomeOk)

        val corrections = store.listActiveCorrectionsMap()
        assertEquals("call Arjun", corrections["call origin"])
        assertEquals("DeepSeek", corrections["deep seek"])

        assertTrue(hotwordsFile.isFile)
        assertEquals(2, hotwordsFile.readLines().filter { it.isNotBlank() }.size)
        assertTrue(store.listUnreflectedEpisodes().isEmpty())
        assertTrue(store.listJournal().any { it.type == "reflection" })
    }

    @Test
    fun `promotes shortcut after 3 successful matching tool episodes and ignores echoed or failed`() {
        val store = InMemoryMindStore(clockMs = { 50_000L })

        // 2 failed/echoed episodes + 3 successful episodes for "torch"
        store.addEpisode(
            Episode(
                timestampMs = 1000L,
                liveTranscript = "turn on flashlight",
                accurateTranscript = "turn on flashlight",
                chosenTranscript = "turn on flashlight",
                reply = "turn on flashlight",
                toolName = "torch",
                toolArgsJson = "{\"state\":\"on\"}",
                echoed = true,
                outcomeOk = true,
            )
        )
        store.addEpisode(
            Episode(
                timestampMs = 2000L,
                liveTranscript = "turn on flashlight",
                accurateTranscript = "turn on flashlight",
                chosenTranscript = "turn on flashlight",
                reply = "Error",
                toolName = "torch",
                toolArgsJson = "{\"state\":\"on\"}",
                echoed = false,
                outcomeOk = false,
            )
        )
        repeat(3) { i ->
            store.addEpisode(
                Episode(
                    timestampMs = 3000L + i * 1000L,
                    liveTranscript = "turn on the flashlight",
                    accurateTranscript = "turn on the flashlight",
                    chosenTranscript = "turn on the flashlight",
                    reply = "Flashlight on.",
                    toolName = "torch",
                    toolArgsJson = "{\"state\":\"on\"}",
                    echoed = false,
                    outcomeOk = true,
                )
            )
        }

        val worker = ReflectionWorker(store)
        val report = worker.reflectOnce()

        assertEquals(1, report.shortcutsPromoted)
        val shortcuts = store.listActiveShortcuts()
        assertEquals(1, shortcuts.size)
        assertEquals("torch", shortcuts.first().toolName)
        assertEquals("{\"state\":\"on\"}", shortcuts.first().argsJson)
    }

    @Test
    fun `parses llmExtract JSON safely and survives malformed responses`() {
        val store = InMemoryMindStore(clockMs = { 80_000L })
        store.addEpisode(
            Episode(
                timestampMs = 80_000L,
                liveTranscript = "I am allergic to peanuts",
                accurateTranscript = "I am allergic to peanuts",
                chosenTranscript = "I am allergic to peanuts",
                reply = "I will keep that in mind.",
            )
        )

        val workerGood = ReflectionWorker(
            store = store,
            llmExtract = {
                """
                ```json
                {"facts":["User is allergic to peanuts"],"vocab":["anaphylaxis"]}
                ```
                """.trimIndent()
            },
        )
        val report = workerGood.reflectOnce()
        assertEquals(1, report.factsLearned)
        assertEquals(1, report.vocabLearned)
        assertEquals("User is allergic to peanuts", store.listActiveFacts().first().text)

        // Malformed output on next episode does not throw
        store.addEpisode(
            Episode(
                timestampMs = 85_000L,
                liveTranscript = "hello again",
                accurateTranscript = "hello again",
                chosenTranscript = "hello again",
                reply = "Hello!",
            )
        )
        val workerBad = ReflectionWorker(store = store, llmExtract = { "not valid json {{{" })
        val badReport = workerBad.reflectOnce()
        assertFalse(badReport.aborted)
        assertEquals(1, badReport.episodesProcessed)
        assertEquals(0, badReport.factsLearned)
    }
}
