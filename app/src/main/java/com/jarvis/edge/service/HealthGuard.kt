package com.jarvis.edge.service

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

class HealthGuard(
    private val batteryTempC: () -> Float,
    private val availableMb: () -> Long,
) {
    enum class Level {
        NORMAL,
        WARM,
        HOT,
    }

    fun level(): Level {
        val temp = batteryTempC()
        val mem = availableMb()
        return when {
            temp >= HOT_TEMP_C -> Level.HOT
            temp >= WARM_TEMP_C || mem < LOW_MEM_WARM_MB -> Level.WARM
            else -> Level.NORMAL
        }
    }

    fun shouldUnloadChatModel(): Boolean = availableMb() < UNLOAD_MEM_MB

    companion object {
        const val WARM_TEMP_C = 40.0f
        const val HOT_TEMP_C = 45.0f
        const val LOW_MEM_WARM_MB = 500L
        const val UNLOAD_MEM_MB = 900L

        fun fromAndroidContext(context: Context): HealthGuard {
            val appCtx = context.applicationContext
            return HealthGuard(
                batteryTempC = { readBatteryTempC(appCtx) },
                availableMb = { readAvailableMb(appCtx) },
            )
        }

        private fun readBatteryTempC(context: Context): Float {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return 30.0f
            return intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) / 10f
        }

        private fun readAvailableMb(context: Context): Long {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            return info.availMem / (1024L * 1024L)
        }
    }
}
