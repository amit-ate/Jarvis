package com.jarvis.edge.hud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.jarvis.edge.BuildConfig
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.core.ModelPaths
import com.jarvis.edge.service.JarvisService
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class HudActivity : ComponentActivity(), SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var lightSensor: Sensor? = null
    private var proxSensor: Sensor? = null
    private var accelSensor: Sensor? = null
    private val currentLux = mutableFloatStateOf(100f)
    private var nearEnteredAtMs: Long = 0L
    private var lastAccelMag: Float = 9.81f

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        startJarvisService()
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveUi()

        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        lightSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)
        proxSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        ensurePermissionsAndStart()

        setContent {
            val state by JarvisService.state.collectAsState()
            val message by JarvisService.message.collectAsState()
            val transcript by JarvisService.transcript.collectAsState()
            val reply by JarvisService.reply.collectAsState()
            val micLevel by JarvisService.micLevel.collectAsState()
            val searching by JarvisService.searching.collectAsState()
            val activeExpert by JarvisService.activeExpert.collectAsState()
            val setup by JarvisService.setup.collectAsState()
            val mindState by MindUiBridge.state.collectAsState()
            val fillWhite by JarvisService.screenFillWhite.collectAsState()
            val brightnessOverride by JarvisService.brightnessOverride.collectAsState()
            val lux by currentLux
            var clockText by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                val fmt = DateTimeFormatter.ofPattern("h:mm a|EEE d MMM", Locale.US)
                while (true) {
                    clockText = java.time.LocalDateTime.now().format(fmt)
                    delay(10_000L)
                }
            }

            LaunchedEffect(lux, state, fillWhite, brightnessOverride) {
                val lp = window.attributes
                lp.screenBrightness = when {
                    fillWhite -> 1.0f
                    brightnessOverride != null -> brightnessOverride!!
                    else -> hudBrightness(lux, state)
                }
                window.attributes = lp
            }

            // Safety net: the camera fill light never stays on for more than 10 seconds.
            LaunchedEffect(fillWhite) {
                if (fillWhite) { delay(10_000L); JarvisService.clearScreenFill() }
            }
            // Safety net: notice a start-up that hangs.
            var bootStartedAt by remember { mutableStateOf(0L) }
            var now by remember { mutableStateOf(System.currentTimeMillis()) }
            LaunchedEffect(Unit) { while (true) { delay(5_000L); now = System.currentTimeMillis() } }
            LaunchedEffect(message) { if (message.startsWith("Starting")) bootStartedAt = System.currentTimeMillis() }

            val res = mindState.resources
            val hot = !res.healthLevel.equals("NORMAL", true)
            val night = lux <= 3f
            val lowBattery = res.batteryPct in 1..14 && !res.charging
            val calm = hot || powerSave.value || lowBattery || (night && state == AssistantState.IDLE)
            val alert: HudAlert? = when {
                !micGranted.value -> HudAlert.MicDenied()
                state == AssistantState.ERROR -> HudAlert.Error(message)
                message.startsWith("Starting") && bootStartedAt > 0 && now - bootStartedAt > 180_000L -> HudAlert.SlowStart()
                crash.value != null -> HudAlert.Crashed(crash.value!!)
                isDegradedStatus(message) -> HudAlert.Degraded(message)
                hot -> HudAlert.Hot(res.healthLevel)
                else -> null
            }

            val setupNow = setup
            if (fillWhite) {
                Box(Modifier.fillMaxSize().background(Color.White).clickable { JarvisService.clearScreenFill() })
            } else if (setupNow != null) {
                JarvisTheme {
                    SetupScreen(
                        progress = setupNow,
                        freeBytes = freeBytesFor(ModelPaths.rootFor(BuildConfig.DEBUG)),
                        onRetry = { JarvisService.setupRetry(this@HudActivity, it) },
                        onStartWithout = { JarvisService.setupStartWithout(this@HudActivity) },
                        onWifiOnly = { JarvisService.setupWifiOnly(this@HudActivity, it) },
                    )
                }
            } else {
                JarvisTheme {
                    HomeHud(
                        state = state,
                        searching = searching,
                        micLevel = micLevel,
                        transcript = transcript,
                        reply = reply,
                        status = message,
                        clockText = clockText,
                        expert = activeExpert,
                        health = res.healthLevel,
                        factCount = mindState.facts.size,
                        battery = res.batteryPct,
                        night = night,
                        calm = calm,
                        alert = alert,
                        onAlertAction = { a ->
                            when (a) {
                                is HudAlert.MicDenied -> startActivity(
                                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + packageName)),
                                )
                                is HudAlert.Crashed -> { crashLog.dismiss(); crash.value = null }
                                is HudAlert.Error, is HudAlert.SlowStart -> restartJarvis()
                                else -> Unit
                            }
                        },
                        onOrbTap = {
                            if (state == AssistantState.SPEAKING) JarvisService.requestStopSpeaking(this@HudActivity)
                            else if (state == AssistantState.IDLE) JarvisService.requestManualWake(this@HudActivity)
                        },
                        onChat = { startActivity(Intent(this@HudActivity, ChatActivity::class.java)) },
                        onMind = { startActivity(Intent(this@HudActivity, MindActivity::class.java)) },
                        onSetup = { startActivity(Intent(this@HudActivity, SetupActivity::class.java)) },
                    )
                }
            }
        }
    }

    private val crashLog by lazy { com.jarvis.edge.core.CrashLog(java.io.File(filesDir, com.jarvis.edge.core.CrashLog.FILE_NAME)) }
    private val crash = mutableStateOf<com.jarvis.edge.core.CrashInfo?>(null)
    private val micGranted = mutableStateOf(true)
    private val powerSave = mutableStateOf(false)

    /** Stops and starts the service: the fix offered when Jarvis is stuck or in an error state. */
    private fun restartJarvis() {
        stopService(Intent(this, JarvisService::class.java))
        window.decorView.postDelayed({ startJarvisService() }, 1_500L)
    }

    /** Free space on the volume holding [root]; the nearest existing folder answers while the root itself is not made yet. */
    private fun freeBytesFor(root: File): Long {
        var dir: File? = root
        while (dir != null && !dir.exists()) dir = dir.parentFile
        return dir?.usableSpace ?: 0L
    }

    private fun refreshSafetyState() {
        micGranted.value = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        powerSave.value = (getSystemService(POWER_SERVICE) as? android.os.PowerManager)?.isPowerSaveMode == true
        crash.value = crashLog.latest()
    }

    override fun onResume() {
        super.onResume()
        refreshSafetyState()
        applyImmersiveUi()
        lightSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        proxSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accelSensor?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                val prevLux = currentLux.floatValue
                currentLux.floatValue = event.values[0]
                JarvisService.latestLux = event.values[0]
                if (abs(event.values[0] - prevLux) > 25f) {
                    JarvisService.latestMotionMs = System.currentTimeMillis()
                }
            }
            Sensor.TYPE_PROXIMITY -> {
                val maxRange = proxSensor?.maximumRange ?: 5f
                val isNear = event.values[0] < maxRange
                JarvisService.latestNearProximity = isNear
                val now = System.currentTimeMillis()
                if (isNear) {
                    nearEnteredAtMs = now
                } else if (nearEnteredAtMs > 0L) {
                    val held = now - nearEnteredAtMs
                    nearEnteredAtMs = 0L
                    JarvisService.latestMotionMs = now
                    if (held < 1000L && JarvisService.state.value == AssistantState.IDLE) {
                        JarvisService.requestManualWake(this)
                    }
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val mag = sqrt(x * x + y * y + z * z)
                if (abs(mag - lastAccelMag) > 1.4f) {
                    JarvisService.latestMotionMs = System.currentTimeMillis()
                }
                lastAccelMag = mag
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun applyImmersiveUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    private fun ensurePermissionsAndStart() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startJarvisService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startJarvisService() {
        val intent = Intent(this, JarvisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
