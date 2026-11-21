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
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.service.JarvisService
import kotlinx.coroutines.delay
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
            val mindState by MindUiBridge.state.collectAsState()
            val fillWhite by JarvisService.screenFillWhite.collectAsState()
            val brightnessOverride by JarvisService.brightnessOverride.collectAsState()
            val lux by currentLux
            var clockText by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
                while (true) {
                    clockText = LocalTime.now().format(fmt)
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

            if (fillWhite) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White),
                )
            } else {
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .combinedClickable(
                                interactionSource = interaction,
                                indication = null,
                                onClick = {
                                    if (state == AssistantState.SPEAKING) {
                                        JarvisService.requestStopSpeaking(this@HudActivity)
                                    } else if (state == AssistantState.IDLE) {
                                        JarvisService.requestManualWake(this@HudActivity)
                                    }
                                },
                                onLongClick = {
                                    startActivity(Intent(this@HudActivity, SetupActivity::class.java))
                                },
                            )
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (state == AssistantState.IDLE && clockText.isNotEmpty()) {
                            Text(
                                text = clockText,
                                color = Color(0xFF64748B),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(16.dp))
                        }

                        JarvisOrb(state = state, micLevel = micLevel, searching = searching)
                        Spacer(Modifier.height(20.dp))

                        Text(
                            text = if (searching) "SEARCHING" else state.name,
                            color = if (searching) Color(0xFFF59E0B) else Color(0xFF38BDF8),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))

                        if (transcript.isNotBlank()) {
                            Text(
                                text = "\"$transcript\"",
                                color = Color(0xFF94A3B8),
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(6.dp))
                        }

                        Text(
                            text = reply.ifBlank { message },
                            color = Color(0xFFE2E8F0),
                            fontSize = 18.sp,
                            textAlign = TextAlign.Center,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            startActivity(Intent(this@HudActivity, MindActivity::class.java))
                        },
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF0F172A),
                            contentColor = Color(0xFF38BDF8),
                        ),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 18.dp, end = 16.dp),
                    ) {
                        Text(
                            text = "MIND (${mindState.facts.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            startActivity(Intent(this@HudActivity, ChatActivity::class.java))
                        },
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFF334155)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF0F172A),
                            contentColor = Color(0xFF38BDF8),
                        ),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 18.dp, start = 16.dp),
                    ) {
                        Text(text = "CHAT", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Text(
                        text = "Expert: $activeExpert • ${mindState.resources.healthLevel}",
                        color = Color(0xFF64748B),
                        fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 14.dp),
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
