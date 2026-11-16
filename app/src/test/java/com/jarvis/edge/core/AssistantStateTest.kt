package com.jarvis.edge.core

import com.jarvis.edge.core.AssistantState.ERROR
import com.jarvis.edge.core.AssistantState.IDLE
import com.jarvis.edge.core.AssistantState.LISTENING
import com.jarvis.edge.core.AssistantState.SPEAKING
import com.jarvis.edge.core.AssistantState.THINKING
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AssistantStateTest {

    @Test
    fun `idle can only start listening or error`() {
        assertTrue(IDLE.canMoveTo(LISTENING))
        assertTrue(IDLE.canMoveTo(ERROR))
        assertFalse(IDLE.canMoveTo(SPEAKING))
        assertFalse(IDLE.canMoveTo(THINKING))
    }

    @Test
    fun `every state can return to idle`() {
        AssistantState.values().forEach { assertTrue(it.canMoveTo(IDLE)) }
    }

    @Test
    fun `forward turn transitions follow pipeline order`() {
        assertTrue(LISTENING.canMoveTo(THINKING))
        assertTrue(THINKING.canMoveTo(SPEAKING))
        assertTrue(SPEAKING.canMoveTo(LISTENING))
        assertFalse(LISTENING.canMoveTo(SPEAKING))
        assertFalse(THINKING.canMoveTo(LISTENING))
    }
}
