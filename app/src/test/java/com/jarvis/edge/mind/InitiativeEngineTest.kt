package com.jarvis.edge.mind

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InitiativeEngineTest {

    @Test
    fun `due reminders fire during quiet hours while non-reminders are blocked`() {
        var now = 1_000_000L
        var hour = 2 // 2:00 AM quiet hours
        val store = InMemoryMindStore(clockMs = { now })
        val engine = InitiativeEngine(
            store = store,
            clockMs = { now },
            hourOfDayProvider = { hour },
        )

        // Thermal warning + greeting should be suppressed during quiet hours (2 AM)
        val hotSnap = SelfStateSnapshot(batteryTempC = 44.5f, batteryPct = 10, charging = false)
        val suppressed = engine.evaluate(
            InitiativeEngine.InitiativeInput(
                isIdle = true,
                snapshot = hotSnap,
                idleMs = 5 * 3600_000L,
                recentPresenceEvent = true,
                newLearningsToday = 3,
            )
        )
        assertNull(suppressed)

        // Add a due reminder -> MUST bypass quiet hours
        store.addReminder("take evening medicine", triggerAtMs = now - 1000L)
        val action = engine.evaluate(
            InitiativeEngine.InitiativeInput(isIdle = true, snapshot = hotSnap)
        )
        assertNotNull(action)
        assertEquals("reminder", action!!.type)
        assertTrue(action.spoken.contains("take evening medicine"))
        assertTrue(store.dueReminders(now).isEmpty())
        assertTrue(store.listJournal().any { it.type == "initiative" && it.targetTable == "reminders" })

        // Switch to daytime (10 AM) -> thermal warning now fires
        hour = 10
        val dayAction = engine.evaluate(
            InitiativeEngine.InitiativeInput(isIdle = true, snapshot = hotSnap)
        )
        assertNotNull(dayAction)
        assertEquals("thermal_warning", dayAction!!.type)
    }

    @Test
    fun `enforces idle mute and hourly rate cap guards`() {
        var now = 10_000_000L
        val store = InMemoryMindStore(clockMs = { now })
        val engine = InitiativeEngine(
            store = store,
            clockMs = { now },
            maxPerHour = 3,
            hourOfDayProvider = { 14 }, // 2 PM
        )
        val normalSnap = SelfStateSnapshot(batteryTempC = 32f, batteryPct = 80)

        // Busy (not idle) -> blocked
        store.addReminder("r1", now)
        assertNull(engine.evaluate(InitiativeEngine.InitiativeInput(isIdle = false, snapshot = normalSnap)))

        // Muted -> blocked until expiry
        engine.muteFor(60_000L, now)
        assertNull(engine.evaluate(InitiativeEngine.InitiativeInput(isIdle = true, snapshot = normalSnap)))

        now += 61_000L
        // Fire 3 reminders to hit maxPerHour = 3
        store.addReminder("r2", now)
        store.addReminder("r3", now)
        store.addReminder("r4", now)

        assertEquals("Reminder: r1", engine.evaluate(InitiativeEngine.InitiativeInput(isIdle = true, snapshot = normalSnap))?.spoken)
        assertEquals("Reminder: r2", engine.evaluate(InitiativeEngine.InitiativeInput(isIdle = true, snapshot = normalSnap))?.spoken)
        assertEquals("Reminder: r3", engine.evaluate(InitiativeEngine.InitiativeInput(isIdle = true, snapshot = normalSnap))?.spoken)

        // 4th within same hour is rate-capped!
        assertNull(engine.evaluate(InitiativeEngine.InitiativeInput(isIdle = true, snapshot = normalSnap)))

        // Advance > 60 min -> 4th reminder fires!
        now += 3_601_000L
        val fourth = engine.evaluate(InitiativeEngine.InitiativeInput(isIdle = true, snapshot = normalSnap))
        assertNotNull(fourth)
        assertEquals("Reminder: r4", fourth!!.spoken)
    }

    @Test
    fun `evaluates low battery greeting after absence and daily recap`() {
        var now = 50_000_000L
        val store = InMemoryMindStore(clockMs = { now })
        val engine = InitiativeEngine(
            store = store,
            clockMs = { now },
            maxPerHour = 5,
            hourOfDayProvider = { 15 },
        )

        val lowBatSnap = SelfStateSnapshot(batteryTempC = 32f, batteryPct = 12, charging = false)
        val a1 = engine.evaluate(InitiativeEngine.InitiativeInput(isIdle = true, snapshot = lowBatSnap))
        assertEquals("low_battery", a1?.type)

        val okSnap = SelfStateSnapshot(batteryTempC = 32f, batteryPct = 90, charging = true)
        val a2 = engine.evaluate(
            InitiativeEngine.InitiativeInput(
                isIdle = true,
                snapshot = okSnap,
                idleMs = 5 * 3600_000L,
                recentPresenceEvent = true,
                newLearningsToday = 2,
            )
        )
        assertEquals("greeting", a2?.type)

        val a3 = engine.evaluate(
            InitiativeEngine.InitiativeInput(
                isIdle = true,
                snapshot = okSnap,
                idleMs = 5 * 3600_000L,
                recentPresenceEvent = true,
                newLearningsToday = 2,
            )
        )
        assertEquals("daily_recap", a3?.type)
        assertTrue(a3!!.spoken.contains("2 new things"))

        // Second recap in same 24h is suppressed
        val a4 = engine.evaluate(
            InitiativeEngine.InitiativeInput(
                isIdle = true,
                snapshot = okSnap,
                idleMs = 1000L,
                recentPresenceEvent = true,
                newLearningsToday = 2,
            )
        )
        assertNull(a4)
    }
}
