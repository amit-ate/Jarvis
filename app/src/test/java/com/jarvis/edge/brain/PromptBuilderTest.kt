package com.jarvis.edge.brain

import com.jarvis.edge.tools.Tool
import com.jarvis.edge.tools.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptBuilderTest {

    private class FakeTool(
        override val name: String,
        override val description: String,
        override val params: Map<String, List<String>?> = emptyMap(),
    ) : Tool {
        override suspend fun run(args: Map<String, String>) = ToolResult("ok", true)
    }

    private val realisticTools = listOf(
        FakeTool("get_time", "Speak time"),
        FakeTool("get_date", "Speak date"),
        FakeTool("set_timer", "Set timer", mapOf("minutes" to null, "seconds" to null)),
        FakeTool("set_alarm", "Set alarm", mapOf("hour" to null, "minute" to null)),
        FakeTool("cancel_timers", "Cancel timers"),
        FakeTool("torch", "Flashlight", mapOf("state" to listOf("on", "off"))),
        FakeTool("set_volume", "Volume %", mapOf("percent" to null)),
        FakeTool("set_brightness", "Brightness %", mapOf("percent" to null)),
        FakeTool("battery_status", "Battery info"),
        FakeTool("light_level", "Lux reading"),
        FakeTool("remember", "Save note", mapOf("text" to null)),
        FakeTool("recall", "Search notes", mapOf("query" to null)),
        FakeTool("ir_send", "Send IR", mapOf("device" to listOf("tv", "ac"), "command" to null)),
        FakeTool("read_text", "OCR text", mapOf("camera" to listOf("front", "back"))),
        FakeTool("look", "Describe scene", mapOf("camera" to listOf("front", "back"), "question" to null)),
    )

    private val builder = PromptBuilder(realisticTools, templateName = "chatml")

    @Test
    fun `system prompt stays within 200 tokens by the 4-chars-per-token estimate`() {
        val sys = builder.system()
        assertTrue(sys.length <= 800, "Expected system length <= 800, got ${sys.length}:\n$sys")
    }

    @Test
    fun `prompt for a new turn starts with the previous prompt's system block`() {
        val first = builder.prompt(emptyList(), "hi")
        val second = builder.prompt(listOf(Turn("hi", "SAY: Hello.")), "what is two plus two")
        assertTrue(first.startsWith(builder.system()))
        assertTrue(second.startsWith(builder.system()))
        assertEquals(builder.system(), first.substring(0, builder.system().length))
    }

    @Test
    fun `history is limited to the last 3 turns`() {
        val history = listOf(
            Turn("u1", "SAY: a1"),
            Turn("u2", "SAY: a2"),
            Turn("u3", "SAY: a3"),
            Turn("u4", "SAY: a4"),
        )
        val p = builder.prompt(history, "u5")
        assertFalse("u1" in p)
        assertTrue("u2" in p && "u3" in p && "u4" in p && "u5" in p)
    }

    @Test
    fun `grammar restricts tool names and enumerated argument values`() {
        val g = builder.grammar()
        assertTrue("\"torch\"" in g.replace("\\", ""))
        assertTrue("on" in g && "off" in g)
        assertTrue("front" in g && "back" in g)
        for (line in g.lines().filter { it.isNotBlank() }) {
            var inString = false
            var inClass = false
            var escaped = false
            for (ch in line) {
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\') {
                    escaped = true
                } else if (inString) {
                    if (ch == '"') inString = false
                } else if (inClass) {
                    if (ch == ']') inClass = false
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '[' -> inClass = true
                    }
                }
            }
            assertFalse(inString, "Unclosed GBNF string literal on line: $line")
            assertFalse(inClass, "Unclosed GBNF character class on line: $line")
        }
    }
}
