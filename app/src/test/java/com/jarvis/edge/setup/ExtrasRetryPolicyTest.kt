package com.jarvis.edge.setup

import com.jarvis.edge.setup.ExtrasRetryPolicy.EXTRAS_RETRY_COOLDOWN_MS
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtrasRetryPolicyTest {

    private val now = 10_000_000_000L

    @Test
    fun allowedToAllowedDoesNotRun() {
        assertFalse(ExtrasRetryPolicy.shouldRun(NetState.ALLOWED, NetState.ALLOWED, now, null))
    }

    @Test
    fun waitingToAllowedRuns() {
        assertTrue(ExtrasRetryPolicy.shouldRun(NetState.WAITING_FOR_WIFI, NetState.ALLOWED, now, null))
    }

    @Test
    fun offlineToAllowedRuns() {
        assertTrue(ExtrasRetryPolicy.shouldRun(NetState.OFFLINE, NetState.ALLOWED, now, null))
    }

    @Test
    fun allowedToWaitingDoesNotRun() {
        assertFalse(ExtrasRetryPolicy.shouldRun(NetState.ALLOWED, NetState.WAITING_FOR_WIFI, now, null))
    }

    @Test
    fun withinCooldownAfterFailedRunDoesNotRun() {
        val failedAt = now - EXTRAS_RETRY_COOLDOWN_MS + 1
        assertFalse(ExtrasRetryPolicy.shouldRun(NetState.WAITING_FOR_WIFI, NetState.ALLOWED, now, failedAt))
    }

    @Test
    fun afterCooldownRuns() {
        val failedAt = now - EXTRAS_RETRY_COOLDOWN_MS
        assertTrue(ExtrasRetryPolicy.shouldRun(NetState.WAITING_FOR_WIFI, NetState.ALLOWED, now, failedAt))
    }

    @Test
    fun cooldownIsSixHours() {
        assertTrue(ExtrasRetryPolicy.inCooldown(now, now - 6L * 60 * 60 * 1000 + 1))
        assertFalse(ExtrasRetryPolicy.inCooldown(now, now - 6L * 60 * 60 * 1000))
        assertFalse(ExtrasRetryPolicy.inCooldown(now, null))
    }
}
