package com.jarvis.edge.hud

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MindUiBridgeTest {

    @BeforeEach
    fun setUp() {
        MindUiBridge.resetForTest()
    }

    @AfterEach
    fun tearDown() {
        MindUiBridge.resetForTest()
    }

    @Test
    fun `publish updates state flow with full ui models`() {
        val published = MindUiState(
            learningPaused = true,
            journal = listOf(
                JournalItemUi(
                    id = 11L,
                    type = "fact",
                    summary = "Learned user prefers dark mode",
                    timestampMs = 1_700_000_000_000L,
                    undone = false,
                ),
            ),
            facts = listOf(
                FactItemUi(
                    id = 21L,
                    text = "Lives in Springfield",
                    confidence = 0.92f,
                    createdAtMs = 1_700_000_000_000L,
                ),
            ),
            vocab = listOf(
                VocabItemUi(id = 31L, phrase = "Jarvis", source = "user"),
            ),
            corrections = listOf(
                CorrectionItemUi(id = 41L, idKey = 401L, heard = "jar this", meant = "Jarvis", hitCount = 3),
            ),
            shortcuts = listOf(
                ShortcutItemUi(
                    id = 51L,
                    utterance = "bedtime",
                    toolName = "set_brightness",
                    argsSummary = "level=0.05",
                    hitCount = 5,
                ),
            ),
            versions = listOf(
                BrainVersionUi(
                    id = 61L,
                    label = "v2-tool-lora",
                    adapterPath = "/sdcard/Jarvis/models/adapters/tool.gguf",
                    evalScore = 0.88f,
                    echoRate = 0.02f,
                    toolAccuracy = 0.91f,
                    active = true,
                ),
            ),
            resources = ResourceStatusUi(
                batteryPct = 84,
                charging = true,
                batteryTempC = 32.5f,
                maxCpuTempC = 48.0f,
                healthLevel = "NORMAL",
                availRamMb = 2150L,
                totalRamMb = 5600L,
                littleCpuMhz = 1612,
                bigCpuMhz = 1804,
                activeModel = "qwen2.5-0.5b-instruct-q4_0",
                activeExperts = listOf("TOOL_USE", "MEMORY"),
                lux = 120.5f,
                nearProximity = false,
                lastReflectionMs = 1_700_000_123_000L,
                episodeCount = 14,
                statusNotice = "Reflection completed",
            ),
        )

        MindUiBridge.publish(published)

        assertEquals(published, MindUiBridge.state.value)
        assertTrue(MindUiBridge.state.value.learningPaused)
        assertEquals(84, MindUiBridge.state.value.resources.batteryPct)
        assertEquals(listOf("TOOL_USE", "MEMORY"), MindUiBridge.state.value.resources.activeExperts)
    }

    @Test
    fun `actions are forwarded cleanly when handler is attached`() {
        val calls = mutableListOf<String>()
        MindUiBridge.actions = object : MindUiActions {
            override fun undoJournal(id: Long) { calls += "undoJournal:$id" }
            override fun deleteFact(id: Long) { calls += "deleteFact:$id" }
            override fun deleteVocab(id: Long) { calls += "deleteVocab:$id" }
            override fun deleteCorrection(id: Long) { calls += "deleteCorrection:$id" }
            override fun deleteShortcut(id: Long) { calls += "deleteShortcut:$id" }
            override fun activateVersion(id: Long) { calls += "activateVersion:$id" }
            override fun setLearningPaused(paused: Boolean) { calls += "setLearningPaused:$paused" }
            override fun exportJson() { calls += "exportJson" }
            override fun triggerReflection() { calls += "triggerReflection" }
            override fun refresh() { calls += "refresh" }
        }

        MindUiBridge.undoJournal(1L)
        MindUiBridge.deleteFact(2L)
        MindUiBridge.deleteVocab(3L)
        MindUiBridge.deleteCorrection(4L)
        MindUiBridge.deleteShortcut(5L)
        MindUiBridge.activateVersion(6L)
        MindUiBridge.setLearningPaused(true)
        MindUiBridge.exportJson()
        MindUiBridge.triggerReflection()
        MindUiBridge.refresh()

        assertEquals(
            listOf(
                "undoJournal:1",
                "deleteFact:2",
                "deleteVocab:3",
                "deleteCorrection:4",
                "deleteShortcut:5",
                "activateVersion:6",
                "setLearningPaused:true",
                "exportJson",
                "triggerReflection",
                "refresh",
            ),
            calls,
        )
    }

    @Test
    fun `forwarding methods are null-safe no-ops when actions are unset`() {
        assertNull(MindUiBridge.actions)
        val initial = MindUiState(learningPaused = false)
        MindUiBridge.publish(initial)

        MindUiBridge.undoJournal(10L)
        MindUiBridge.deleteFact(20L)
        MindUiBridge.deleteVocab(30L)
        MindUiBridge.deleteCorrection(40L)
        MindUiBridge.deleteShortcut(50L)
        MindUiBridge.activateVersion(60L)
        MindUiBridge.setLearningPaused(true)
        MindUiBridge.exportJson()
        MindUiBridge.triggerReflection()
        MindUiBridge.refresh()

        assertEquals(initial, MindUiBridge.state.value)
        assertFalse(MindUiBridge.state.value.learningPaused)
    }

    @Test
    fun `resetForTest clears actions and restores default state`() {
        MindUiBridge.actions = object : MindUiActions {
            override fun undoJournal(id: Long) = Unit
            override fun deleteFact(id: Long) = Unit
            override fun deleteVocab(id: Long) = Unit
            override fun deleteCorrection(id: Long) = Unit
            override fun deleteShortcut(id: Long) = Unit
            override fun activateVersion(id: Long) = Unit
            override fun setLearningPaused(paused: Boolean) = Unit
            override fun exportJson() = Unit
            override fun triggerReflection() = Unit
            override fun refresh() = Unit
        }
        MindUiBridge.publish(MindUiState(learningPaused = true))

        MindUiBridge.resetForTest()

        assertNull(MindUiBridge.actions)
        assertEquals(MindUiState(), MindUiBridge.state.value)
    }
}
