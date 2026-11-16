package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ToolCallParserTest {

    @Test
    fun `parser`() {
        assertEquals(
            ModelOutput.Say("Hello there."),
            parseModelOutput("SAY: Hello there."),
        )
        assertEquals(
            ModelOutput.Call(ToolCall("torch", mapOf("state" to "off"))),
            parseModelOutput("""{"tool":"torch","args":{"state":"off"}}"""),
        )
        assertEquals(
            ModelOutput.Say("just text"),
            parseModelOutput("just text"),
        )
    }
}
