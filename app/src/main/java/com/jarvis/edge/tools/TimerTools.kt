package com.jarvis.edge.tools

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArrayList

class TimerBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        onTimerFired?.invoke("Your timer is done.")
    }

    companion object {
        @Volatile
        var onTimerFired: ((String) -> Unit)? = null
    }
}

class TimerScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scheduledIds = CopyOnWriteArrayList<Int>()
    private var nextId = 2000

    fun scheduleAt(triggerAtMs: Long) {
        val id = synchronized(this) { nextId++ }
        val pi = pendingIntentFor(id)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
        scheduledIds.add(id)
    }

    fun cancelAll(): Int {
        var count = 0
        for (id in scheduledIds) {
            val pi = pendingIntentFor(id)
            alarmManager.cancel(pi)
            pi.cancel()
            count++
        }
        scheduledIds.clear()
        return count
    }

    private fun pendingIntentFor(id: Int): PendingIntent {
        val intent = Intent(context, TimerBroadcastReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, id, intent, flags)
    }
}

class SetTimerTool(private val scheduler: TimerScheduler) : Tool {
    override val name = "set_timer"
    override val description = "Set a countdown timer"
    override val params = mapOf("minutes" to null, "seconds" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val mins = args["minutes"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val secs = args["seconds"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val totalSecs = mins * 60L + secs
        if (totalSecs <= 0) {
            return ToolResult("Please give a positive duration for the timer.", ok = false)
        }
        scheduler.scheduleAt(System.currentTimeMillis() + totalSecs * 1000L)
        val label = buildList {
            if (mins > 0) add("$mins ${if (mins == 1) "minute" else "minutes"}")
            if (secs > 0) add("$secs ${if (secs == 1) "second" else "seconds"}")
        }.joinToString(" and ")
        return ToolResult("Timer set for $label.", ok = true)
    }
}

class SetAlarmTool(private val scheduler: TimerScheduler) : Tool {
    override val name = "set_alarm"
    override val description = "Set an alarm for an hour and minute"
    override val params = mapOf("hour" to null, "minute" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val hour = args["hour"]?.toIntOrNull()
        val minute = args["minute"]?.toIntOrNull() ?: 0
        if (hour == null || hour !in 0..23 || minute !in 0..59) {
            return ToolResult("That doesn't sound like a valid alarm time.", ok = false)
        }
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }
        scheduler.scheduleAt(cal.timeInMillis)
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val ampm = if (hour < 12) "AM" else "PM"
        val formatted = "%d:%02d %s".format(displayHour, minute, ampm)
        return ToolResult("Alarm set for $formatted.", ok = true)
    }
}

class CancelTimersTool(private val scheduler: TimerScheduler) : Tool {
    override val name = "cancel_timers"
    override val description = "Cancel all active timers and alarms"
    override val params: Map<String, List<String>?> = emptyMap()

    override suspend fun run(args: Map<String, String>): ToolResult {
        val count = scheduler.cancelAll()
        val msg = if (count == 0) "You don't have any active timers." else "All timers cancelled."
        return ToolResult(msg, ok = true)
    }
}
