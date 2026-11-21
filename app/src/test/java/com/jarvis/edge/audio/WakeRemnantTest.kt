package com.jarvis.edge.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WakeRemnantTest {
    @Test
    fun stripsWakeWordTail() {
        assertEquals("what's my battery?", WakeRemnant.clean("Jarvis, what's my battery?"))
        assertEquals("WHAT'S MY AGE", WakeRemnant.clean("VIS WHAT'S MY AGE"))
        assertEquals("tell me a joke", WakeRemnant.clean("hey jarvis tell me a joke"))
    }

    @Test
    fun repairsClippedWhats() {
        assertEquals("WHAT'S MY BATTERY", WakeRemnant.clean("'S MY BATTERY"))
        assertEquals("What's the time", WakeRemnant.clean("'s the time"))
    }

    @Test
    fun leavesNormalSpeechAlone() {
        assertEquals("Is it raining", WakeRemnant.clean("Is it raining"))
        assertEquals("visit my mom", WakeRemnant.clean("visit my mom"))
        assertEquals("Jarvis", WakeRemnant.clean("Jarvis"))
    }
}
