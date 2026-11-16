package com.jarvis.edge.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HealthGuardTest {

    @Test
    fun `below 40 C is normal`() {
        val guard = HealthGuard(batteryTempC = { 39.9f }, availableMb = { 1500L })
        assertEquals(HealthGuard.Level.NORMAL, guard.level())
    }

    @Test
    fun `40 to 44_9 C is warm`() {
        assertEquals(
            HealthGuard.Level.WARM,
            HealthGuard(batteryTempC = { 40.0f }, availableMb = { 1500L }).level(),
        )
        assertEquals(
            HealthGuard.Level.WARM,
            HealthGuard(batteryTempC = { 44.9f }, availableMb = { 1500L }).level(),
        )
    }

    @Test
    fun `45 C and above is hot`() {
        assertEquals(
            HealthGuard.Level.HOT,
            HealthGuard(batteryTempC = { 45.0f }, availableMb = { 1500L }).level(),
        )
    }

    @Test
    fun `under 500 MB available counts as warm`() {
        assertEquals(
            HealthGuard.Level.WARM,
            HealthGuard(batteryTempC = { 35.0f }, availableMb = { 499L }).level(),
        )
        assertEquals(
            HealthGuard.Level.NORMAL,
            HealthGuard(batteryTempC = { 35.0f }, availableMb = { 500L }).level(),
        )
    }

    @Test
    fun `shouldUnloadChatModel is true only under 900 MB available`() {
        assertTrue(HealthGuard(batteryTempC = { 35.0f }, availableMb = { 899L }).shouldUnloadChatModel())
        assertFalse(HealthGuard(batteryTempC = { 35.0f }, availableMb = { 900L }).shouldUnloadChatModel())
    }
}
