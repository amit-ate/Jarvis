package com.jarvis.edge.mind

import java.util.Calendar

/**
 * Evaluates proactive spoken initiatives with idle, mute, quiet-hours, and hourly rate-cap guards.
 */
class InitiativeEngine(
    private val store: MindRepository,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val maxPerHour: Int = 3,
    private val hourOfDayProvider: (Long) -> Int = { now ->
        Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
    },
) {

    data class InitiativeInput(
        val isIdle: Boolean = true,
        val snapshot: SelfStateSnapshot,
        val idleMs: Long = 0L,
        val recentPresenceEvent: Boolean = false,
        val newLearningsToday: Int = 0,
    )

    data class InitiativeAction(
        val type: String,
        val spoken: String,
        val reminderId: Long? = null,
    )

    private val recentFiredMs = ArrayDeque<Long>()
    @Volatile
    private var muteUntilMs: Long = 0L
    private var lastThermalWarningMs: Long = Long.MIN_VALUE / 2
    private var lastLowBatteryWarningMs: Long = Long.MIN_VALUE / 2
    private var lastGreetingMs: Long = Long.MIN_VALUE / 2
    private var lastRecapMs: Long = Long.MIN_VALUE / 2

    fun muteFor(durationMs: Long, nowMs: Long = clockMs()) {
        val until = nowMs + durationMs.coerceAtLeast(0L)
        if (until > muteUntilMs) {
            muteUntilMs = until
        }
    }

    fun evaluate(input: InitiativeInput): InitiativeAction? {
        val now = clockMs()
        if (!input.isIdle) return null
        if (now < muteUntilMs) return null

        pruneOldFires(now)
        if (recentFiredMs.size >= maxPerHour) return null

        // 1. Due reminders always bypass quiet hours.
        val due = store.dueReminders(now).firstOrNull()
        if (due != null) {
            store.markReminderFired(due.id)
            recordFired(now)
            val spoken = "Reminder: ${due.text}"
            store.recordInitiativeJournal("Fired reminder: ${due.text}", "reminders", due.id)
            return InitiativeAction(type = "reminder", spoken = spoken, reminderId = due.id)
        }

        // Quiet hours (23:00 .. 06:59) block all non-reminder initiatives.
        val hour = hourOfDayProvider(now)
        if (hour >= 23 || hour < 7) return null

        // 2. Thermal warning or low battery warning.
        if (input.snapshot.batteryTempC >= THERMAL_WARN_C &&
            now - lastThermalWarningMs >= HEALTH_WARN_COOLDOWN_MS
        ) {
            lastThermalWarningMs = now
            recordFired(now)
            val spoken = "Heads up, my battery temperature is ${input.snapshot.batteryTempC.toInt()} degrees Celsius."
            store.recordInitiativeJournal("Thermal warning (${input.snapshot.batteryTempC}C)")
            return InitiativeAction(type = "thermal_warning", spoken = spoken)
        }

        if (input.snapshot.batteryPct <= LOW_BATTERY_PCT &&
            !input.snapshot.charging &&
            now - lastLowBatteryWarningMs >= HEALTH_WARN_COOLDOWN_MS
        ) {
            lastLowBatteryWarningMs = now
            recordFired(now)
            val spoken = "My battery is down to ${input.snapshot.batteryPct} percent. Please plug me in soon."
            store.recordInitiativeJournal("Low battery warning (${input.snapshot.batteryPct}%)")
            return InitiativeAction(type = "low_battery", spoken = spoken)
        }

        // 3. Greeting after long absence (>= 4h) when presence is detected.
        if (input.idleMs >= LONG_ABSENCE_MS &&
            input.recentPresenceEvent &&
            now - lastGreetingMs >= LONG_ABSENCE_MS
        ) {
            lastGreetingMs = now
            recordFired(now)
            val spoken = "Welcome back. Let me know if you need anything."
            store.recordInitiativeJournal("Greeted user after absence")
            return InitiativeAction(type = "greeting", spoken = spoken)
        }

        // 4. Daily learning recap once per 24h when new learnings exist and user is present.
        if (input.newLearningsToday > 0 &&
            input.recentPresenceEvent &&
            now - lastRecapMs >= DAILY_RECAP_COOLDOWN_MS
        ) {
            lastRecapMs = now
            recordFired(now)
            val noun = if (input.newLearningsToday == 1) "thing" else "things"
            val spoken = "Today I learned ${input.newLearningsToday} new $noun. Ask what I learned today if you'd like details."
            store.recordInitiativeJournal("Offered daily learning recap (${input.newLearningsToday})")
            return InitiativeAction(type = "daily_recap", spoken = spoken)
        }

        return null
    }

    private fun pruneOldFires(now: Long) {
        val cutoff = now - ONE_HOUR_MS
        while (recentFiredMs.isNotEmpty() && recentFiredMs.first() < cutoff) {
            recentFiredMs.removeFirst()
        }
    }

    private fun recordFired(now: Long) {
        recentFiredMs.addLast(now)
    }

    companion object {
        const val THERMAL_WARN_C = 43.0f
        const val LOW_BATTERY_PCT = 15
        const val LONG_ABSENCE_MS = 4L * 3600_000L
        const val ONE_HOUR_MS = 3600_000L
        const val HEALTH_WARN_COOLDOWN_MS = 30L * 60_000L
        const val DAILY_RECAP_COOLDOWN_MS = 24L * 3600_000L
    }
}
