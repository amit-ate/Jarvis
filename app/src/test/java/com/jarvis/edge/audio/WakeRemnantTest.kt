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
    fun dropsStrayFragmentBeforeRequest() {
        assertEquals("WHAT DO YOU KNOW ABOUT ME", WakeRemnant.clean("Y WHAT DO YOU KNOW ABOUT ME"))
        assertEquals("is it raining", WakeRemnant.clean("s is it raining"))
        assertEquals("THANK YOU", WakeRemnant.clean("Y THANK YOU"))
        assertEquals("I want tea", WakeRemnant.clean("I want tea"))
        assertEquals("so what is that", WakeRemnant.clean("so what is that"))
    }

    @Test
    fun fixesCommonMishears() {
        assertEquals("tell me a joke.", WakeRemnant.clean("Tell me a joe."))
        assertEquals("tell me a joke", WakeRemnant.clean("Jarvis, tell me a joe"))
        assertEquals("tell me a joke about cats", WakeRemnant.clean("tell me a joke about cats"))
    }

    @Test
    fun leavesNormalSpeechAlone() {
        assertEquals("Is it raining", WakeRemnant.clean("Is it raining"))
        assertEquals("visit my mom", WakeRemnant.clean("visit my mom"))
        assertEquals("Jarvis", WakeRemnant.clean("Jarvis"))
    }
}
