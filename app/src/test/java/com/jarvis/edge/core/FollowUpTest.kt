package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FollowUpTest {
    private fun ends(s: String) = TurnController.ENDS_CONVERSATION.containsMatchIn(s)

    @Test
    fun `goodbyes close the conversation`() {
        listOf("Thanks.", "thank you Jarvis", "OK thanks", "That's all", "bye", "Stop", "never mind", "No thanks", "Y THANK YOU").forEach {
            assertTrue(ends(it), it)
        }
    }

    @Test
    fun `real requests keep listening`() {
        listOf("Thanks, and what about tomorrow?", "Stop the timer at five", "What is the capital of France?", "Tell me a joke").forEach {
            assertFalse(ends(it), it)
        }
    }
}
