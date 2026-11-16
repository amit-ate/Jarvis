package com.jarvis.edge.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.provider.Settings
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class TorchTool(
    private val context: Context,
    private val legacyFallback: ((Boolean) -> Boolean)? = null,
) : Tool {
    override val name = "torch"
    override val description = "Turn the rear flashlight on or off"
    override val params = mapOf("state" to listOf("on", "off"))

    override suspend fun run(args: Map<String, String>): ToolResult {
        val on = args["state"] == "on"
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val rearId = runCatching {
            cm.cameraIdList.firstOrNull { id ->
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                facing == CameraCharacteristics.LENS_FACING_BACK && flash
            } ?: "0"
        }.getOrDefault("0")

        val ok = runCatching {
            cm.setTorchMode(rearId, on)
            true
        }.getOrElse {
            legacyFallback?.invoke(on) ?: false
        }

        return if (ok) {
            ToolResult(if (on) "Flashlight on." else "Flashlight off.", ok = true)
        } else {
            ToolResult("I couldn't control the flashlight.", ok = false)
        }
    }
}

class SetVolumeTool(private val context: Context) : Tool {
    override val name = "set_volume"
    override val description = "Set media volume percentage (0 to 100)"
    override val params = mapOf("percent" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val pct = args["percent"]?.toIntOrNull()?.coerceIn(0, 100)
            ?: return ToolResult("Tell me a volume percentage between 0 and 100.", ok = false)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = ((pct / 100.0) * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return ToolResult("Volume set to $pct percent.", ok = true)
    }
}

class SetBrightnessTool(
    private val context: Context,
    private val onWindowBrightness: ((Float) -> Unit)? = null,
) : Tool {
    override val name = "set_brightness"
    override val description = "Set screen brightness percentage (0 to 100)"
    override val params = mapOf("percent" to null)

    override suspend fun run(args: Map<String, String>): ToolResult {
        val pct = args["percent"]?.toIntOrNull()?.coerceIn(0, 100)
            ?: return ToolResult("Tell me a brightness percentage between 0 and 100.", ok = false)

        onWindowBrightness?.invoke((pct / 100f).coerceIn(0.02f, 1.0f))
        if (Settings.System.canWrite(context)) {
            val value = ((pct / 100.0) * 255.0).roundToInt().coerceIn(1, 255)
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }
        return ToolResult("Brightness set to $pct percent.", ok = true)
    }
}

class BatteryStatusTool(private val context: Context) : Tool {
    override val name = "battery_status"
    override val description = "Report battery percentage and temperature"
    override val params: Map<String, List<String>?> = emptyMap()

    override suspend fun run(args: Map<String, String>): ToolResult {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return ToolResult("I couldn't read the battery status.", ok = false)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        return ToolResult(
            "Battery is at $pct percent, and ${"%.1f".format(tempC)} degrees Celsius.",
            ok = true,
        )
    }
}

class LightLevelTool(private val context: Context) : Tool {
    override val name = "light_level"
    override val description = "Read ambient light level in lux"
    override val params: Map<String, List<String>?> = emptyMap()

    override suspend fun run(args: Map<String, String>): ToolResult {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
            ?: return ToolResult("No light sensor available.", ok = false)

        val lux = withTimeoutOrNull(1000L) {
            suspendCancellableCoroutine<Float> { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sm.unregisterListener(this)
                        if (cont.isActive) cont.resume(event.values[0])
                    }

                    override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
                }
                sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sm.unregisterListener(listener) }
            }
        } ?: return ToolResult("Couldn't read the ambient light sensor.", ok = false)

        return ToolResult("Ambient light is ${lux.roundToInt()} lux.", ok = true)
    }
}
