package com.jarvis.edge.tools

import com.jarvis.edge.brain.FastIntents
import com.jarvis.edge.brain.ToolCall
import com.jarvis.edge.service.HealthGuard
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisionToolsTest {

    @Test
    fun `read_text with no text found says so`() = runBlocking {
        val tool = ReadTextTool(
            captureAndRead = { _, _ -> "" },
        )
        val res = tool.run(mapOf("camera" to "back"))
        assertTrue(res.ok)
        assertEquals("I don't see any text.", res.spoken)
    }

    @Test
    fun `look refuses when HealthGuard says the phone is hot`() = runBlocking {
        val hotGuard = HealthGuard(batteryTempC = { 46.0f }, availableMb = { 1500L })
        var invoked = false
        val tool = LookTool(
            captureAndDescribe = { _, _, _, _ -> invoked = true },
            healthGuard = hotGuard,
        )
        val res = tool.run(mapOf("camera" to "front", "question" to "what am i holding"))
        assertFalse(res.ok)
        assertFalse(invoked)
        assertEquals("I'm too warm for that right now. Give me a few minutes.", res.spoken)
    }

    @Test
    fun `fast intents for vision`() {
        assertEquals(
            ToolCall("read_text", mapOf("camera" to "back")),
            FastIntents.match("read this"),
        )
        assertEquals(
            ToolCall("look", mapOf("camera" to "front", "question" to "what am i holding")),
            FastIntents.match("what am I holding"),
        )
    }
}
