package com.jarvis.edge.tools

import com.jarvis.edge.brain.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    @Test
    fun `unknown tool gives a spoken failure and does not throw`() = runBlocking {
        val r = ToolRegistry(emptyList()).run(ToolCall("nope", emptyMap()))
        assertFalse(r.ok)
        assertEquals("I can't do that yet.", r.spoken)
    }

    @Test
    fun `argument outside allowed values is rejected before the tool runs`() = runBlocking {
        var ran = false
        val torchTool = object : Tool {
            override val name = "torch"
            override val description = "Toggle torch"
            override val params = mapOf("state" to listOf("on", "off"))
            override suspend fun run(args: Map<String, String>): ToolResult {
                ran = true
                return ToolResult("ok", true)
            }
        }
        val r = ToolRegistry(listOf(torchTool)).run(ToolCall("torch", mapOf("state" to "blue")))
        assertFalse(r.ok)
        assertFalse(ran)
    }
}
