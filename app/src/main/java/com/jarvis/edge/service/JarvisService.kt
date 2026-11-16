package com.jarvis.edge.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.jarvis.edge.audio.Earcon
import com.jarvis.edge.audio.GatedWakeWord
import com.jarvis.edge.audio.MicStream
import com.jarvis.edge.audio.SherpaLiveTranscriber
import com.jarvis.edge.audio.SherpaMoonshineTranscriber
import com.jarvis.edge.audio.SherpaSpeaker
import com.jarvis.edge.audio.SherpaWakeWord
import com.jarvis.edge.audio.SileroVad
import com.jarvis.edge.audio.SpeechSegmenter
import com.jarvis.edge.brain.Brain
import com.jarvis.edge.brain.FastIntents
import com.jarvis.edge.brain.LlamaJniEngine
import com.jarvis.edge.brain.LlmEngine
import com.jarvis.edge.brain.PromptBuilder
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.core.ModelPaths
import com.jarvis.edge.core.TurnController
import com.jarvis.edge.core.canMoveTo
import com.jarvis.edge.hud.SetupActivity
import com.jarvis.edge.tools.BatteryStatusTool
import com.jarvis.edge.tools.CancelTimersTool
import com.jarvis.edge.tools.LightLevelTool
import com.jarvis.edge.tools.LookTool
import com.jarvis.edge.tools.NotesStore
import com.jarvis.edge.tools.ReadTextTool
import com.jarvis.edge.tools.RecallTool
import com.jarvis.edge.tools.RememberTool
import com.jarvis.edge.tools.SetAlarmTool
import com.jarvis.edge.tools.SetBrightnessTool
import com.jarvis.edge.tools.SetTimerTool
import com.jarvis.edge.tools.SetVolumeTool
import com.jarvis.edge.tools.TimerBroadcastReceiver
import com.jarvis.edge.tools.TimerScheduler
import com.jarvis.edge.tools.ToolRegistry
import com.jarvis.edge.tools.TorchTool
import com.jarvis.edge.tools.GetDateTool
import com.jarvis.edge.tools.GetTimeTool
import com.jarvis.edge.tools.ir.IrCodes
import com.jarvis.edge.tools.ir.IrTool
import com.jarvis.edge.vision.CameraSnapshot
import com.jarvis.edge.vision.LlamaMtmdVlm
import com.jarvis.edge.vision.MlKitOcr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class JarvisService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var voiceJob: Job? = null

    private var speaker: SherpaSpeaker? = null
    private var vad: SileroVad? = null
    private var wakeWord: SherpaWakeWord? = null
    private var liveTranscriber: SherpaLiveTranscriber? = null
    private var accurateTranscriber: SherpaMoonshineTranscriber? = null
    private var llmEngine: LlmEngine? = null
    private var notesStore: NotesStore? = null
    private var cameraSnapshot: CameraSnapshot? = null
    private var mlKitOcr: MlKitOcr? = null

    private val manualWakeRequested = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Jarvis ready"))
        acquireWakeLock()
        scheduleWatchdog(this)
        updateState(AssistantState.IDLE, "Starting Jarvis...")
        startVoicePipeline()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SPEAKING -> speaker?.stop()
            ACTION_MANUAL_WAKE -> manualWakeRequested.set(true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        voiceJob?.cancel()
        serviceScope.cancel()
        TimerBroadcastReceiver.onTimerFired = null
        runCatching { speaker?.release() }
        runCatching { wakeWord?.release() }
        runCatching { liveTranscriber?.release() }
        runCatching { accurateTranscriber?.release() }
        runCatching { vad?.release() }
        runCatching { llmEngine?.unload() }
        runCatching { notesStore?.close() }
        runCatching { cameraSnapshot?.shutdown() }
        runCatching { mlKitOcr?.close() }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVoicePipeline() {
        if (voiceJob?.isActive == true) return
        voiceJob = serviceScope.launch {
            val paths = ModelPaths(File(ModelPaths.DEFAULT_ROOT))
            val missingSpeech = paths.missingSpeech()
            if (missingSpeech.isNotEmpty()) {
                updateState(
                    AssistantState.ERROR,
                    "Missing speech model: ${missingSpeech.joinToString(", ")}",
                )
                return@launch
            }

            val healthGuard = HealthGuard.fromAndroidContext(this@JarvisService)
            val vadInstance = SileroVad(File(paths.root, "vad/silero_vad.onnx")).also { vad = it }
            val kwsInstance = SherpaWakeWord(
                kwsDir = File(paths.root, "kws"),
                onInitThread = { LlamaJniEngine.pinCurrentThreadToBigCores() },
            ).also { wakeWord = it }
            val gatedWake = GatedWakeWord(vad = vadInstance, inner = kwsInstance)

            val live = SherpaLiveTranscriber(
                modelDir = File(paths.root, "asr-zipformer"),
                onInitThread = { LlamaJniEngine.pinCurrentThreadToBigCores() },
            ).also { liveTranscriber = it }

            val moonshineDir = File(paths.root, "asr-moonshine")
            val accurate = if (File(moonshineDir, "encode.int8.onnx").exists()) {
                runCatching {
                    SherpaMoonshineTranscriber(
                        modelDir = moonshineDir,
                        onInitThread = { LlamaJniEngine.pinCurrentThreadToBigCores() },
                    ).also { accurateTranscriber = it }
                }.getOrNull()
            } else {
                null
            }

            val ttsSpeaker = SherpaSpeaker(
                ttsDir = File(paths.root, "tts"),
                cacheDir = File(cacheDir, "tts_cache"),
            ).also { speaker = it }
            launch(Dispatchers.IO) { runCatching { ttsSpeaker.prewarmFixedPhrases() } }

            TimerBroadcastReceiver.onTimerFired = { msg ->
                serviceScope.launch { ttsSpeaker.speak(msg) }
            }

            val missingLlm = paths.missingLlm()
            val llmFile = File(paths.root, paths.llmRelative())
            val jniEngine = LlamaJniEngine().also { llmEngine = it }
            var startStatus = "Ready"
            if (missingLlm.isEmpty()) {
                if (!healthGuard.shouldUnloadChatModel()) {
                    runCatching { jniEngine.load(llmFile, contextTokens = 2048) }
                        .onFailure {
                            startStatus = "LLM failed to load (${paths.llmRelative()}) — basic commands only"
                        }
                } else {
                    startStatus = "Low memory — chat model deferred"
                }
            } else {
                startStatus = "Missing ${paths.llmRelative()} — basic commands only"
            }

            val notes = NotesStore(this@JarvisService).also { notesStore = it }
            val timerScheduler = TimerScheduler(this@JarvisService)
            val baseIrJson = assets.open("ir/codes.json").bufferedReader().use { it.readText() }
            val irOverrideFile = File(filesDir, SetupActivity.OVERRIDE_FILE_NAME)
            val irCodesProvider = {
                val overrideJson = if (irOverrideFile.exists()) irOverrideFile.readText() else null
                IrCodes.withUserOverrides(baseIrJson, overrideJson)
            }

            val snapshotter = CameraSnapshot(
                context = this@JarvisService,
                onScreenFillWhite = { on -> _screenFillWhite.value = on },
            ).also { cameraSnapshot = it }
            val ocrClient = MlKitOcr().also { mlKitOcr = it }

            val missingVlm = paths.missing().filter { it.startsWith("vlm/") }
            val vlmInstance = if (missingVlm.isEmpty()) {
                LlamaMtmdVlm(
                    modelFile = File(paths.root, "vlm/SmolVLM-256M-Instruct-Q8_0.gguf"),
                    mmprojFile = File(paths.root, "vlm/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf"),
                )
            } else {
                null
            }

            val announceLook: suspend () -> Unit = { ttsSpeaker.speak("Let me take a look.") }
            val tools = listOf(
                GetTimeTool(),
                GetDateTool(),
                SetTimerTool(timerScheduler),
                SetAlarmTool(timerScheduler),
                CancelTimersTool(timerScheduler),
                TorchTool(this@JarvisService),
                SetVolumeTool(this@JarvisService),
                SetBrightnessTool(this@JarvisService) { b -> _brightnessOverride.value = b },
                BatteryStatusTool(this@JarvisService),
                LightLevelTool(this@JarvisService),
                RememberTool(notes),
                RecallTool(notes),
                IrTool(irCodesProvider, IrTool.androidTransmitter(this@JarvisService)),
                ReadTextTool(
                    capturer = snapshotter,
                    ocr = ocrClient,
                    healthGuard = healthGuard,
                    luxProvider = { latestLux },
                    onBeforeCapture = announceLook,
                ),
                LookTool(
                    capturer = snapshotter,
                    vlm = vlmInstance,
                    healthGuard = healthGuard,
                    luxProvider = { latestLux },
                    onBeforeCapture = announceLook,
                ),
            )
            val registry = ToolRegistry(tools)
            val prompts = PromptBuilder(tools, templateName = "chatml")
            val activeLlm = if (missingLlm.isEmpty()) jniEngine else null
            val brain = Brain(
                fast = FastIntents,
                llm = activeLlm,
                prompts = prompts,
                tools = registry,
                healthGuard = healthGuard,
            )

            val mic = MicStream()
            val earcon = Earcon { suppressMs -> mic.suppressFor(suppressMs) }
            val turnController = TurnController(
                segmenter = SpeechSegmenter(vad = vadInstance),
                live = live,
                accurate = accurate,
                brain = brain,
                speaker = ttsSpeaker,
                setState = { next ->
                    updateState(next)
                    updateNotification("Jarvis: ${next.name}")
                },
                onTranscript = { t -> _transcript.value = t },
                onReplyChunk = { r -> _reply.value = r },
            )

            updateState(AssistantState.IDLE, startStatus)

            var turnChannel: Channel<FloatArray>? = null
            var turnJob: Job? = null

            try {
                mic.frames().collect { frame ->
                    _micLevel.value = computeRms(frame)

                    // Do not capture/process mic frames while speaking.
                    if (_state.value == AssistantState.SPEAKING) {
                        return@collect
                    }

                    val activeTurn = turnChannel
                    if (activeTurn != null && turnJob?.isActive == true) {
                        if (_state.value == AssistantState.LISTENING) {
                            activeTurn.trySend(frame)
                        }
                        return@collect
                    }

                    val woken = manualWakeRequested.getAndSet(false) || gatedWake.accept(frame)
                    if (woken) {
                        if (healthGuard.shouldUnloadChatModel() && jniEngine.isLoaded) {
                            jniEngine.unload()
                        } else if (!healthGuard.shouldUnloadChatModel() && !jniEngine.isLoaded && missingLlm.isEmpty()) {
                            ttsSpeaker.speak("One moment.")
                            runCatching { jniEngine.load(llmFile, contextTokens = 2048) }
                        }

                        earcon.playWake()
                        val ch = Channel<FloatArray>(Channel.UNLIMITED)
                        turnChannel = ch
                        turnJob = launch {
                            try {
                                turnController.runTurn(ch.receiveAsFlow())
                            } finally {
                                ch.close()
                                gatedWake.reset()
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                updateState(AssistantState.ERROR, t.message ?: "Microphone stream error")
            }
        }
    }

    private fun computeRms(frame: FloatArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (s in frame) {
            sum += s * s
        }
        return (sqrt(sum / frame.size) * 4.0).toFloat().coerceIn(0f, 1f)
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::EdgeWakeLock").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Assistant",
                NotificationManager.IMPORTANCE_LOW,
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Jarvis Edge")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_edge_channel"
        private const val NOTIFICATION_ID = 1001
        private const val WATCHDOG_REQ_CODE = 3001
        private const val WATCHDOG_INTERVAL_MS = 45_000L

        const val ACTION_STOP_SPEAKING = "com.jarvis.edge.action.STOP_SPEAKING"
        const val ACTION_MANUAL_WAKE = "com.jarvis.edge.action.MANUAL_WAKE"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var latestLux: Float = 100f

        private val _state = MutableStateFlow(AssistantState.IDLE)
        val state: StateFlow<AssistantState> = _state.asStateFlow()

        private val _message = MutableStateFlow("Starting...")
        val message: StateFlow<String> = _message.asStateFlow()

        private val _transcript = MutableStateFlow("")
        val transcript: StateFlow<String> = _transcript.asStateFlow()

        private val _reply = MutableStateFlow("")
        val reply: StateFlow<String> = _reply.asStateFlow()

        private val _micLevel = MutableStateFlow(0f)
        val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

        private val _screenFillWhite = MutableStateFlow(false)
        val screenFillWhite: StateFlow<Boolean> = _screenFillWhite.asStateFlow()

        private val _brightnessOverride = MutableStateFlow<Float?>(null)
        val brightnessOverride: StateFlow<Float?> = _brightnessOverride.asStateFlow()

        fun updateState(next: AssistantState, msg: String? = null) {
            if (_state.value.canMoveTo(next)) {
                _state.value = next
            }
            if (msg != null) {
                _message.value = msg
            }
        }

        fun requestStopSpeaking(context: Context) {
            context.startService(Intent(context, JarvisService::class.java).apply { action = ACTION_STOP_SPEAKING })
        }

        fun requestManualWake(context: Context) {
            context.startService(Intent(context, JarvisService::class.java).apply { action = ACTION_MANUAL_WAKE })
        }

        fun scheduleWatchdog(context: Context) {
            val am = context.getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, WATCHDOG_REQ_CODE, intent, flags)
            val triggerAt = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }
}
