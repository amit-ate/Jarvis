package com.jarvis.edge.mind

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import com.jarvis.edge.service.HealthGuard
import java.io.File

/**
 * Aggregates live hardware, thermal, memory, sensor, and cognitive state into [SelfStateSnapshot].
 * All Android and sysfs reads are guarded so restricted SELinux or JVM tests never throw.
 */
class SelfModel(
    private val context: Context? = null,
    private val store: MindRepository? = null,
    private val activeModelProvider: () -> String = { "google_gemma-3-1b-it-Q4_0" },
    private val activeExpertsProvider: () -> List<String> = { emptyList() },
    private val luxProvider: () -> Float = { 0f },
    private val nearProximityProvider: () -> Boolean = { false },
    private val lastMotionMsProvider: () -> Long = { 0L },
    private val lastReflectionMsProvider: () -> Long = { 0L },
    private val recoveredAtMsProvider: () -> Long? = { null },
    private val uptimeMsProvider: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrDefault(0L)
    },
) {

    fun capture(): SelfStateSnapshot {
        val battery = readBatteryInfo()
        val ram = readRamInfo()
        val maxCpuTemp = readMaxCpuTempC()
        val littleMhz = readCpuFreqMhz(0)
        val bigMhz = readCpuFreqMhz(4)
        val healthLevel = computeHealthLevel(battery.tempC, ram.first)

        val facts = runCatching { store?.countActiveFacts() ?: 0 }.getOrDefault(0)
        val vocab = runCatching { store?.countActiveVocab() ?: 0 }.getOrDefault(0)
        val corrections = runCatching { store?.countActiveCorrections() ?: 0 }.getOrDefault(0)
        val shortcuts = runCatching { store?.countActiveShortcuts() ?: 0 }.getOrDefault(0)
        val episodes = runCatching { store?.countEpisodes() ?: 0 }.getOrDefault(0)
        val paused = runCatching { store?.isLearningPaused() ?: false }.getOrDefault(false)

        return SelfStateSnapshot(
            batteryPct = battery.pct,
            charging = battery.charging,
            batteryTempC = battery.tempC,
            maxCpuTempC = maxCpuTemp,
            healthLevelName = healthLevel.name,
            availRamMb = ram.first,
            totalRamMb = ram.second,
            littleFreqMhz = littleMhz,
            bigFreqMhz = bigMhz,
            activeModel = runCatching { activeModelProvider() }.getOrDefault("google_gemma-3-1b-it-Q4_0"),
            activeExperts = runCatching { activeExpertsProvider() }.getOrDefault(emptyList()),
            factCount = facts,
            vocabCount = vocab,
            correctionCount = corrections,
            shortcutCount = shortcuts,
            episodeCount = episodes,
            lastReflectionMs = runCatching { lastReflectionMsProvider() }.getOrDefault(0L),
            uptimeMs = runCatching { uptimeMsProvider() }.getOrDefault(0L),
            lux = runCatching { luxProvider() }.getOrDefault(0f),
            nearProximity = runCatching { nearProximityProvider() }.getOrDefault(false),
            lastMotionMs = runCatching { lastMotionMsProvider() }.getOrDefault(0L),
            learningPaused = paused,
            recoveredAtMs = runCatching { recoveredAtMsProvider() }.getOrNull(),
        )
    }

    private data class BatteryReading(val pct: Int, val charging: Boolean, val tempC: Float)

    private fun readBatteryInfo(): BatteryReading {
        val ctx = context ?: return BatteryReading(100, false, 30.0f)
        return runCatching {
            val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return BatteryReading(100, false, 30.0f)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (level >= 0 && scale > 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else 100
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300)
            BatteryReading(pct, charging, tempRaw / 10f)
        }.getOrDefault(BatteryReading(100, false, 30.0f))
    }

    private fun readRamInfo(): Pair<Long, Long> {
        val ctx = context ?: return 0L to 0L
        return runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val availMb = info.availMem / (1024L * 1024L)
            val totalMb = info.totalMem / (1024L * 1024L)
            availMb to totalMb
        }.getOrDefault(0L to 0L)
    }

    private fun readMaxCpuTempC(): Float {
        return runCatching {
            val thermalDir = File("/sys/class/thermal")
            val zones = thermalDir.listFiles { _, name -> name.startsWith("thermal_zone") }
                ?: return 0f
            var maxTemp = 0f
            for (zone in zones) {
                val raw = runCatching {
                    File(zone, "temp").readText().trim().toFloatOrNull()
                }.getOrNull() ?: continue
                val tempC = when {
                    raw > 1000f -> raw / 1000f
                    raw > 200f -> raw / 10f
                    else -> raw
                }
                if (tempC in 5f..120f && tempC > maxTemp) {
                    maxTemp = tempC
                }
            }
            maxTemp
        }.getOrDefault(0f)
    }

    private fun readCpuFreqMhz(cpuIndex: Int): Int {
        return runCatching {
            val f = File("/sys/devices/system/cpu/cpu$cpuIndex/cpufreq/scaling_cur_freq")
            if (!f.canRead()) return 0
            val khz = f.readText().trim().toLongOrNull() ?: return 0
            (khz / 1000L).toInt().coerceAtLeast(0)
        }.getOrDefault(0)
    }

    private fun computeHealthLevel(batteryTempC: Float, availRamMb: Long): HealthGuard.Level {
        return when {
            batteryTempC >= HealthGuard.HOT_TEMP_C -> HealthGuard.Level.HOT
            batteryTempC >= HealthGuard.WARM_TEMP_C ||
                (availRamMb in 1 until HealthGuard.LOW_MEM_WARM_MB) -> HealthGuard.Level.WARM
            else -> HealthGuard.Level.NORMAL
        }
    }

    companion object {
        private const val RECOVERY_NOTE_MS = 10L * 60_000L

        /**
         * Compact one-line summary for LLM system prompt injection (strictly <= 120 chars).
         */
        fun toPromptLine(s: SelfStateSnapshot, nowMs: Long = System.currentTimeMillis()): String {
            val chg = if (s.charging) "+" else ""
            val line = "Self: bat=${s.batteryPct}%$chg ${s.batteryTempC.toInt()}C " +
                "ram=${s.availRamMb}/${s.totalRamMb}MB ${s.healthLevelName} " +
                "facts=${s.factCount} vocab=${s.vocabCount}"
            val recovered = s.recoveredAtMs?.let { nowMs - it in 0 until RECOVERY_NOTE_MS } == true
            if (!recovered) return if (line.length <= 120) line else line.substring(0, 120)
            val suffix = " recovered from unexpected shutdown"
            return line.take(120 - suffix.length) + suffix
        }

        /**
         * Natural spoken summary for user-facing status queries.
         */
        fun toSpokenSummary(s: SelfStateSnapshot): String {
            val chargeText = if (s.charging) "charging" else "on battery"
            val pauseText = if (s.learningPaused) " Learning is paused." else ""
            val ramText = if (s.totalRamMb > 0L) {
                "${s.availRamMb} of ${s.totalRamMb} megabytes RAM free"
            } else {
                "RAM telemetry unavailable"
            }
            return "Battery is at ${s.batteryPct} percent, $chargeText, at ${s.batteryTempC.toInt()} degrees Celsius, " +
                "health ${s.healthLevelName.lowercase()}. $ramText. " +
                "I have ${s.factCount} facts, ${s.vocabCount} words, and ${s.shortcutCount} shortcuts learned.$pauseText"
        }
    }
}
