package com.jarvis.edge.tools.ir

import com.jarvis.edge.brain.FastIntents
import com.jarvis.edge.brain.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IrToolTest {

    @Test
    fun `ir_send for an unconfigured device speaks an error and transmits nothing`() = runBlocking {
        val codes = IrCodes("""{"devices":{}}""")
        var transmitted = false
        val tool = IrTool(codes) { _, _ -> transmitted = true }

        val res = tool.run(mapOf("device" to "tv", "command" to "power"))
        assertFalse(res.ok)
        assertFalse(transmitted)
        assertTrue(res.spoken.contains("configured", ignoreCase = true) || res.spoken.contains("setup", ignoreCase = true))
    }

    @Test
    fun `fast intents for TV`() {
        assertEquals(
            ToolCall("ir_send", mapOf("device" to "tv", "command" to "power")),
            FastIntents.match("turn off the tv"),
        )
        assertEquals(
            ToolCall("ir_send", mapOf("device" to "tv", "command" to "volume_up")),
            FastIntents.match("tv volume up"),
        )
    }
}
