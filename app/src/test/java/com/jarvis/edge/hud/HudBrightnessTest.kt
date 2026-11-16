package com.jarvis.edge.hud

import com.jarvis.edge.core.AssistantState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HudBrightnessTest {

    @Test
    fun `dark room and idle dims to the floor`() {
        assertEquals(0.02f, hudBrightness(1f, AssistantState.IDLE))
    }

    @Test
    fun `bright room and idle`() {
        assertEquals(0.35f, hudBrightness(300f, AssistantState.IDLE))
    }

    @Test
    fun `active states are never below 0_25`() {
        assertTrue(hudBrightness(1f, AssistantState.LISTENING) >= 0.25f)
        assertTrue(hudBrightness(1f, AssistantState.THINKING) >= 0.25f)
        assertTrue(hudBrightness(1f, AssistantState.SPEAKING) >= 0.25f)
    }
}
