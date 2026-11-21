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
import com.jarvis.edge.audio.SherpaTransducerTranscriber
import com.jarvis.edge.audio.Transcriber
import com.jarvis.edge.audio.SherpaSpeaker
import com.jarvis.edge.audio.SherpaWakeWord
import com.jarvis.edge.audio.SileroVad
import com.jarvis.edge.audio.SimdAudio
import com.jarvis.edge.audio.SpeechSegmenter
import com.jarvis.edge.audio.TranscriptArbiter
import com.jarvis.edge.brain.Brain
import com.jarvis.edge.brain.ControlVector
import com.jarvis.edge.brain.ControlVectorCodec
import com.jarvis.edge.brain.EmbeddingEngine
import com.jarvis.edge.brain.ExpertDomain
import com.jarvis.edge.brain.ExpertRouter
import com.jarvis.edge.brain.FastIntents
import com.jarvis.edge.brain.KvRestoreResult
import com.jarvis.edge.brain.KvStateManager
import com.jarvis.edge.brain.LlamaEmbeddingEngine
import com.jarvis.edge.brain.LlamaJniEngine
import com.jarvis.edge.brain.LlmEngine
import com.jarvis.edge.brain.PromptBuilder
import com.jarvis.edge.brain.ToolCall
import com.jarvis.edge.core.AssistantState
import com.jarvis.edge.core.ChatLog
import com.jarvis.edge.core.AccurateAsr
import com.jarvis.edge.core.ModelPaths
import com.jarvis.edge.core.VoiceSampleStore
import com.jarvis.edge.core.TurnController
import com.jarvis.edge.core.canMoveTo
import com.jarvis.edge.hud.BrainVersionUi
import com.jarvis.edge.hud.CorrectionItemUi
import com.jarvis.edge.hud.FactItemUi
import com.jarvis.edge.hud.JournalItemUi
import com.jarvis.edge.hud.MindUiActions
import com.jarvis.edge.hud.MindUiBridge
import com.jarvis.edge.hud.MindUiState
import com.jarvis.edge.hud.ResourceStatusUi
import com.jarvis.edge.hud.SetupActivity
import com.jarvis.edge.hud.ShortcutItemUi
import com.jarvis.edge.hud.VocabItemUi
import com.jarvis.edge.mind.BrainEvolver
import com.jarvis.edge.mind.EmbeddingMigrator
import com.jarvis.edge.mind.Episode
import com.jarvis.edge.mind.PersonalFacts
import com.jarvis.edge.mind.HotwordsWriter
import com.jarvis.edge.mind.InitiativeEngine
import com.jarvis.edge.mind.LearnedShortcut
import com.jarvis.edge.mind.MindForgetTool
import com.jarvis.edge.mind.MindRecallTool
import com.jarvis.edge.mind.MindRememberTool
import com.jarvis.edge.mind.MindStatusTool
import com.jarvis.edge.mind.ModelVersion
import com.jarvis.edge.mind.OnDeviceEvalGate
import com.jarvis.edge.mind.ReflectionWorker
import com.jarvis.edge.mind.Recovery
import com.jarvis.edge.mind.RemindTool
import com.jarvis.edge.mind.SelfModel
import com.jarvis.edge.mind.SqliteMindStore
import com.jarvis.edge.mind.VectorMath
import com.jarvis.edge.mind.WorkingState
import com.jarvis.edge.mind.WorkingStateStore
import com.jarvis.edge.tools.BatteryStatusTool
import com.jarvis.edge.tools.CancelTimersTool
import com.jarvis.edge.tools.GetDateTool
import com.jarvis.edge.tools.GetTimeTool
import com.jarvis.edge.tools.GetWeatherTool
import com.jarvis.edge.tools.LightLevelTool
import com.jarvis.edge.tools.LookTool
import com.jarvis.edge.tools.ReadTextTool
import com.jarvis.edge.tools.SetAlarmTool
import com.jarvis.edge.tools.SetBrightnessTool
import com.jarvis.edge.tools.SetTimerTool
import com.jarvis.edge.tools.SetVolumeTool
import com.jarvis.edge.tools.TimerBroadcastReceiver
import com.jarvis.edge.tools.TimerScheduler
import com.jarvis.edge.tools.ToolRegistry
import com.jarvis.edge.tools.TorchTool
import com.jarvis.edge.tools.WebSearchTool
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class JarvisService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var voiceJob: Job? = null
    private var reflectionJob: Job? = null
    private var initiativeJob: Job? = null

    private var speaker: SherpaSpeaker? = null
    private var vad: SileroVad? = null
    private var turnVad: SileroVad? = null
    private var wakeWord: SherpaWakeWord? = null
    private var liveTranscriber: SherpaLiveTranscriber? = null
    private var accurateTranscriber: Transcriber? = null
    @Volatile private var personalSpeech: com.jarvis.edge.core.PersonalSpeechModel? = null
    /** "Run auto learning": starts a reflection pass now and reports what was learned (set once the pipeline is up). */
    @Volatile private var learnNowFn: (() -> Boolean)? = null
    /** Plays the "searching the web" cue (set once the earcon exists). */
    @Volatile private var searchCue: (() -> Unit)? = null
    private var llmEngine: LlmEngine? = null
    private var embeddingEngine: EmbeddingEngine? = null
    private var mindStore: SqliteMindStore? = null
    private var cameraSnapshot: CameraSnapshot? = null
    private var mlKitOcr: MlKitOcr? = null

    private val manualWakeRequested = AtomicBoolean(false)
    private val initiativeSpeaking = AtomicBoolean(false)
    /** Set after an answer: the mic reopens for a follow-up question without the wake word. */
    private val followUpRequested = AtomicBoolean(false)
    /** Set by the voice-training screen: record the next utterance as a sample of this sentence instead of a request. */
    private val enrollLabel = java.util.concurrent.atomic.AtomicReference<String?>(null)
    /** A typed (text mode) request is being answered: voice turns wait. */
    private val textTurnActive = AtomicBoolean(false)
    @Volatile private var textInputHandler: ((text: String, speak: Boolean) -> Unit)? = null
    private val evolutionRunning = AtomicBoolean(false)

    // Checkpoint hooks installed once the pipeline is built; used by onTrimMemory/onDestroy.
    @Volatile private var saveWorkingStateFn: (() -> Unit)? = null
    @Volatile private var saveKvFn: (() -> Boolean)? = null
    @Volatile private var recovery: Recovery? = null
    @Volatile private var openEpisodeId: Long? = null

    @Volatile private var lastInteractionMs: Long = System.currentTimeMillis()
    @Volatile private var lastReflectionMs: Long = 0L
    @Volatile private var lastLiveRaw: String = ""
    @Volatile private var lastAccurateRaw: String = ""
    @Volatile private var statusNotice: String = ""
    @Volatile private var diagFrames = 0
    @Volatile private var diagVadFrames = 0
    @Volatile private var diagMaxRms = 0f

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
            ACTION_ENROLL -> intent.getStringExtra(EXTRA_TEXT)?.trim()?.takeIf { it.isNotEmpty() }?.let { enrollLabel.set(it) }
            ACTION_TEXT_INPUT -> {
                val text = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
                val speak = intent.getBooleanExtra(EXTRA_SPEAK, false)
                if (text.isNotEmpty()) {
                    val handler = textInputHandler
                    if (handler != null) {
                        handler(text, speak)
                    } else {
                        chatLog(this).addUser(text, typed = true)
                        chatLog(this).addReplyChunk("I'm still starting up. Try again in a moment.")
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            serviceScope.launch(Dispatchers.IO) {
                runCatching { saveWorkingStateFn?.invoke() }
                runCatching { saveKvFn?.invoke() }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        // Checkpoint while the mind and model are still open; the KV save is bounded to 2 s.
        runCatching { saveWorkingStateFn?.invoke() }
        val kvSave = saveKvFn?.let { save -> Thread { runCatching { save() } }.apply { start() } }
        kvSave?.join(KV_SAVE_TIMEOUT_MS)
        val kvSaveStuck = kvSave?.isAlive == true
        runCatching { recovery?.onCleanStop() }
        MindUiBridge.actions = null
        reflectionJob?.cancel()
        initiativeJob?.cancel()
        voiceJob?.cancel()
        serviceScope.cancel()
        TimerBroadcastReceiver.onTimerFired = null
        runCatching { speaker?.release() }
        runCatching { wakeWord?.release() }
        runCatching { liveTranscriber?.release() }
        runCatching {
            when (val t = accurateTranscriber) {
                is SherpaMoonshineTranscriber -> t.release()
                is SherpaTransducerTranscriber -> t.release()
                else -> Unit
            }
        }
        runCatching { vad?.release() }
        runCatching { turnVad?.release() }
        if (!kvSaveStuck) runCatching { llmEngine?.unload() }
        runCatching { embeddingEngine?.unload() }
        runCatching { mindStore?.close() }
        runCatching { cameraSnapshot?.shutdown() }
        runCatching { mlKitOcr?.close() }
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startVoicePipeline() {
        if (voiceJob?.isActive == true) return
        voiceJob = serviceScope.launch {
            val modelRoot = File(ModelPaths.DEFAULT_ROOT)
            val paths = ModelPaths(modelRoot, ModelPaths.resolveLlmFileName(modelRoot))
            android.util.Log.i("JarvisBrain", "chat model: ${paths.llmFileName} (template ${PromptBuilder.templateFor(paths.llmFileName)})")
            val missingSpeech = paths.missingSpeech()
            if (missingSpeech.isNotEmpty()) {
                updateState(
                    AssistantState.ERROR,
                    "Missing speech model: ${missingSpeech.joinToString(", ")}",
                )
                return@launch
            }

            val healthGuard = HealthGuard.fromAndroidContext(this@JarvisService)
            val mind = SqliteMindStore(this@JarvisService).also { mindStore = it }
            // Facts mined by reflection that don't refer to the user were Jarvis's own answers ("A spider has eight
            // legs", "Ten miles is 40.6 km") stored as facts about the user: drop them.
            runCatching {
                mind.listActiveFacts(500)
                    .filter { it.sourceEpisodeId != null && !ReflectionWorker.mentionsUser(it.text) }
                    .forEach {
                        mind.deleteFact(it.id)
                        android.util.Log.i("JarvisMind", "removed fact not about the user: ${it.text}")
                    }
            }
            val adaptersDir = File(paths.root, "adapters")
            val kvDir = File(filesDir, "kv")
            val recoveryHelper = Recovery(repo = mind, adaptersDir = adaptersDir, kvDir = kvDir, clockMs = System::currentTimeMillis)
                .also { recovery = it }
            val recoveryReport = recoveryHelper.onStart()
            val recoveredAtMs: Long? = if (recoveryReport.unclean) System.currentTimeMillis() else null
            android.util.Log.i(
                "JarvisMind",
                "Recovery: unclean=${recoveryReport.unclean} interrupted=${recoveryReport.interruptedEpisodes} " +
                    "orphans=${recoveryReport.orphansRemoved} temps=${recoveryReport.tempsRemoved}",
            )
            if (mind.listModelVersions().isEmpty()) {
                mind.recordModelVersion(
                    baseModel = paths.llmFileName,
                    adapterPath = null,
                    evalScore = 0.0f,
                    echoRate = 0.0f,
                    toolAccuracy = 0.0f,
                    active = true,
                )
            }

            // Optional GGUF embedding model under /sdcard/Jarvis/models/embed/*.gguf; fallback to deterministic 128-d vector.
            val embedFile = File(paths.root, "embed").listFiles { f -> f.isFile && f.extension == "gguf" }?.firstOrNull()
            val ggufEmbedder = if (embedFile != null) {
                runCatching {
                    LlamaEmbeddingEngine().apply { load(embedFile) }
                }.getOrNull()?.also { embeddingEngine = it }
            } else {
                null
            }
            val embedFn: (String) -> FloatArray = { text ->
                if (ggufEmbedder != null && ggufEmbedder.isLoaded) {
                    runCatching { ggufEmbedder.embed(text) }.getOrElse { VectorMath.simpleHashEmbed(text) }
                } else {
                    VectorMath.simpleHashEmbed(text)
                }
            }
            var loadedEmbedDim = 0
            if (ggufEmbedder != null && ggufEmbedder.isLoaded) {
                loadedEmbedDim = runCatching { ggufEmbedder.embed("jarvis").size }.getOrDefault(0)
                android.util.Log.i("JarvisMind", "Neural embedder loaded (${loadedEmbedDim}-d) from ${embedFile?.name}")
            }

            val tokensFile = File(paths.root, "asr-zipformer/tokens.txt")
            val hotwordsWriter = if (tokensFile.isFile) HotwordsWriter.fromTokensFile(tokensFile) else null
            val hotwordsFile = File(filesDir, "zipformer_hotwords.txt")
            regenerateHotwords(mind, hotwordsWriter, hotwordsFile)

            // Pin always-on VAD and WakeWord to little cores (cpu0..3) so big cores stay cool and idle.
            val vadInstance = SileroVad(
                modelFile = File(paths.root, "vad/silero_vad.onnx"),
                threshold = 0.22f,
                numThreads = 1,
                minRms = 0.005f,
            ).also { vad = it }
            val turnVadInstance = SileroVad(
                modelFile = File(paths.root, "vad/silero_vad.onnx"),
                threshold = 0.35f,
                numThreads = 1,
                minRms = 0.010f,
            ).also { turnVad = it }
            val kwsInstance = SherpaWakeWord(
                kwsDir = File(paths.root, "kws"),
                numThreads = 2,
                onInitThread = { SimdAudio.pinCurrentThreadToLittleCores() },
            ).also { wakeWord = it }
            val loggingVad = object : com.jarvis.edge.audio.Vad {
                override fun isSpeech(frame: FloatArray): Boolean =
                    vadInstance.isSpeech(frame).also { if (it) diagVadFrames++ }
                override fun reset() = vadInstance.reset()
            }
            val gatedWake = GatedWakeWord(vad = loggingVad, inner = kwsInstance)

            val liveAsr = ModelPaths.resolveLiveAsr(paths.root)
            android.util.Log.i("JarvisVoice", "live ASR: " + liveAsr.dir.name)
            val live = SherpaLiveTranscriber(
                modelDir = liveAsr.dir,
                numThreads = 2,
                onInitThread = { LlamaJniEngine.pinCurrentThreadToBigCores() },
                hotwordsFile = hotwordsFile.takeIf { liveAsr.supportsHotwords },
                hotwordsScore = 2.0f,
            ).also { liveTranscriber = it }

            val accurateAsr = ModelPaths.resolveAccurateAsr(paths.root)
            fun loadAccurate(asr: AccurateAsr): Transcriber = when (asr.kind) {
                AccurateAsr.Kind.TRANSDUCER -> SherpaTransducerTranscriber(
                    modelDir = asr.dir,
                    numThreads = 4,
                    onInitThread = { LlamaJniEngine.pinCurrentThreadToBigCores() },
                )
                AccurateAsr.Kind.MOONSHINE -> SherpaMoonshineTranscriber(
                    modelDir = asr.dir,
                    numThreads = 4,
                    onInitThread = { LlamaJniEngine.pinCurrentThreadToBigCores() },
                )
            }
            var loadedAsr = "none"
            val accurate: Transcriber? = accurateAsr?.let { asr ->
                runCatching { loadAccurate(asr) }
                    .onFailure { android.util.Log.e("JarvisVoice", "accurate ASR ${asr.dir.name} failed to load", it) }
                    .getOrNull()?.also { loadedAsr = asr.dir.name }
                    // A broken custom model must not leave Jarvis deaf: fall back to the next recogniser.
                    ?: ModelPaths.resolveAccurateAsr(paths.root, skip = setOf(asr.dir.name))?.let { fallback ->
                        runCatching { loadAccurate(fallback) }.getOrNull()?.also { loadedAsr = fallback.dir.name }
                    }
            }?.also { accurateTranscriber = it }
            android.util.Log.i("JarvisVoice", "accurate ASR: $loadedAsr (${accurate?.javaClass?.simpleName})")

            val ttsSpeaker = SherpaSpeaker(
                voice = com.jarvis.edge.core.Voices.resolve(
                    paths.root,
                    getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("voice", null),
                ).also { android.util.Log.i("JarvisVoice", "speaking voice: ${it?.id}") },
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

            val expertRouter = ExpertRouter.fromManifestFile(File(adaptersDir, "experts.json"), adaptersDir, steadyAdapters = true)
            fun applyModelVersionToRouter(version: ModelVersion?) {
                val rawPath = version?.adapterPath?.takeIf { it.isNotBlank() }
                val vec = if (rawPath != null) {
                    val candidate = File(rawPath).let { if (it.isAbsolute) it else File(adaptersDir, rawPath) }
                    if (candidate.isFile) ControlVectorCodec.read(candidate) else null
                } else {
                    null
                }
                expertRouter.setEvolvedControlVector(vec)
                _activeExpert.value = expertRouter.activeExpertSummary(ExpertDomain.CONVERSATION)
            }
            applyModelVersionToRouter(mind.listModelVersions().firstOrNull { it.active })

            val initiativeEngine = InitiativeEngine(store = mind)

            val selfModel = SelfModel(
                context = this@JarvisService,
                store = mind,
                activeModelProvider = { paths.llmFileName },
                activeExpertsProvider = { listOf(_activeExpert.value) },
                luxProvider = { latestLux },
                nearProximityProvider = { latestNearProximity },
                lastMotionMsProvider = { latestMotionMs },
                lastReflectionMsProvider = { lastReflectionMs },
                recoveredAtMsProvider = { recoveredAtMs },
            )

            if (loadedEmbedDim > 0) {
                launch(Dispatchers.IO) {
                    SimdAudio.pinCurrentThreadToLittleCores()
                    val migrated = runCatching {
                        EmbeddingMigrator.migrateIfNeeded(mind, loadedEmbedDim, embedFn)
                    }.getOrDefault(0)
                    if (migrated > 0) {
                        android.util.Log.i("JarvisMind", "Migrated $migrated facts to ${loadedEmbedDim}-d embeddings")
                        publishMindState(mind, selfModel)
                    }
                }
            }

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

            // Set once the KV manager exists (it needs the prompt builder, built after the tools).
            var onVersionChanged: () -> Unit = {}
            val announceLook: suspend () -> Unit = { ttsSpeaker.speak("Let me take a look.") }
            val tools = listOf(
                GetTimeTool(),
                GetDateTool(),
                SetTimerTool(timerScheduler),
                SetAlarmTool(timerScheduler),
                CancelTimersTool(timerScheduler),
                RemindTool(mind),
                TorchTool(this@JarvisService),
                SetVolumeTool(this@JarvisService),
                SetBrightnessTool(this@JarvisService) { b -> _brightnessOverride.value = b },
                BatteryStatusTool(this@JarvisService),
                LightLevelTool(this@JarvisService),
                MindRememberTool(mind, embedFn),
                MindRecallTool(mind, embedFn),
                MindForgetTool(mind, embedFn),
                MindStatusTool(
                    store = mind,
                    onLearnNow = { learnNowFn?.invoke() ?: false },
                    selfSnapshotProvider = { selfModel.capture() },
                    onMuteRequested = { durationMs -> initiativeEngine.muteFor(durationMs) },
                    onVersionActivated = { rolledBack ->
                        applyModelVersionToRouter(rolledBack)
                        if (jniEngine.isLoaded && _state.value == AssistantState.IDLE) {
                            runCatching { expertRouter.selectAndActivate("", jniEngine) }
                        }
                        onVersionChanged()
                    },
                ),
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
                GetWeatherTool(),
                WebSearchTool(),
            )
            val registry = ToolRegistry(tools)
            val prompts = PromptBuilder(tools, templateName = PromptBuilder.templateFor(paths.llmFileName))
            val kvManager = KvStateManager(kvDir)
            fun kvKey(): String = kvManager.key(
                llmFile,
                prompts.system(),
                "${expertRouter.appliedSignature(jniEngine)}#${mind.listModelVersions().firstOrNull { it.active }?.id ?: 0}",
            )
            // Single-flight guard shared with the evolution cycle; skipped whenever a turn is in flight.
            // The state check is only a cheap pre-check: the engine lock is what keeps a save out of a decode
            // (saveState uses tryLock and returns false when busy).
            fun saveKv(): Boolean {
                if (!jniEngine.isLoaded || _state.value != AssistantState.IDLE) return false
                if (!evolutionRunning.compareAndSet(false, true)) return false
                return try {
                    _state.value == AssistantState.IDLE &&
                        runCatching { kvManager.save(jniEngine, kvKey()) }.getOrDefault(false)
                } finally {
                    evolutionRunning.set(false)
                }
            }
            onVersionChanged = { runCatching { kvManager.pruneExcept(kvKey()) } }
            saveKvFn = ::saveKv
            if (jniEngine.isLoaded) {
                runCatching {
                    expertRouter.selectAndActivate("", jniEngine)
                    val kvResult = kvManager.restore(jniEngine, kvKey())
                    android.util.Log.i("JarvisMind", "KV restore: $kvResult")
                    if (kvResult == KvRestoreResult.REJECTED) {
                        mind.recordRecoveryJournal("Discarded an unusable saved model memory; rebuilt it from text.")
                    }
                    // A warm-up generate would trim the restored conversation KV back to the fixed prefix.
                    if (kvResult != KvRestoreResult.RESTORED) {
                        jniEngine.generate(prompts.prompt(emptyList(), "hi"), prompts.sayOnlyGrammar(), maxTokens = 1) { false }
                    }
                }
            }
            // Background loops use jniEngine + llmAvailable, never a smart-cast of the nullable activeLlm: the coroutine
            // spill of that smart-cast compiled to a Void cast and crashed the service ~10 min after every start.
            val llmAvailable = missingLlm.isEmpty()
            val activeLlm: LlamaJniEngine? = if (llmAvailable) jniEngine else null
            // Learns this user's words and the recogniser's mistakes on the phone, and fixes transcripts with them.
            val psm = com.jarvis.edge.core.PersonalSpeechModel(
                File(filesDir, "personal_speech.json"),
                runCatching { com.jarvis.edge.core.WordList(assets.open("english_words.txt").use { it.readBytes() }) }
                    .getOrDefault(com.jarvis.edge.core.WordList.EMPTY),
            ).also { personalSpeech = it }
            android.util.Log.i("JarvisVoice", "personal speech model: ${psm.vocabularySize} words, ${psm.confusionCount} confusions")
            fun learnInBackground(block: (com.jarvis.edge.core.PersonalSpeechModel) -> Unit) {
                serviceScope.launch(Dispatchers.IO) { runCatching { block(psm); psm.save() } }
            }
            val brain = Brain(
                fast = FastIntents,
                llm = activeLlm,
                prompts = prompts,
                tools = registry,
                healthGuard = healthGuard,
                log = { msg -> android.util.Log.i("JarvisBrain", msg) },
                expertRouter = expertRouter,
                shortcutMatcher = { userText ->
                    mind.matchShortcut(embedFn(userText), minSim = 0.86f)?.let { shortcut ->
                        parseShortcutToolCall(shortcut)
                    }
                },
                selfStateProvider = { SelfModel.toSpokenSummary(selfModel.capture()) },
                memoryProvider = { query -> buildMemoryContext(mind, embedFn(query)) },
                personalFacts = PersonalFacts(mind, embedFn),
                prefetchLookups = true,
                onLookupStarted = {
                    _searching.value = true
                    searchCue?.invoke()
                },
                onEntityCorrected = { heard, meant ->
                    learnInBackground { it.learnCorrection(heard, meant) }
                    runCatching {
                        mind.learnCorrection(heard, meant)
                        mind.learnVocab(meant, source = "corrected")
                        if (regenerateHotwords(mind, hotwordsWriter, hotwordsFile) > 0) live.updateHotwords(hotwordsFile)
                    }.onFailure { android.util.Log.w("JarvisBrain", "learning correction failed", it) }
                },
                onEpisodeComplete = { userText, reply, toolCall, echoed ->
                    _activeExpert.value = expertRouter.activeExpertSummary(expertRouter.classify(userText))
                    val argsJson = toolCall?.let { JSONObject(it.args).toString() }
                    val openId = openEpisodeId
                    openEpisodeId = null
                    if (openId != null) {
                        mind.completeEpisode(openId, reply, toolCall?.name, argsJson, echoed)
                    } else mind.addEpisode(
                        Episode(
                            timestampMs = System.currentTimeMillis(),
                            liveTranscript = lastLiveRaw,
                            accurateTranscript = lastAccurateRaw,
                            chosenTranscript = userText,
                            reply = reply,
                            toolName = toolCall?.name,
                            toolArgsJson = argsJson,
                            echoed = echoed,
                            outcomeOk = !echoed,
                        )
                    )
                    lastLiveRaw = ""
                    lastAccurateRaw = ""
                    serviceScope.launch(Dispatchers.IO) {
                        runCatching { saveWorkingStateFn?.invoke() }
                        publishMindState(mind, selfModel)
                    }
                },
            )

            val reflectionWorker = ReflectionWorker(
                store = mind,
                embed = embedFn,
                hotwordsWriter = hotwordsWriter,
                hotwordsOutFile = hotwordsFile,
                llmExtract = if (llmAvailable) {
                    { batch ->
                        if (jniEngine.isLoaded && _state.value == AssistantState.IDLE) {
                            val extractPrompt = prompts.prompt(
                                history = emptyList(),
                                user = "From the user's own words below, list facts the user states about themselves " +
                                    "(name, age, family, likes, places, routines) as short sentences starting with \"User\", " +
                                    "and unusual names they use. Ignore questions and general knowledge. Reply only with JSON " +
                                    "{\"facts\":[],\"vocab\":[]}.\n$batch",
                            )
                            val sb = StringBuilder()
                            runCatching {
                                jniEngine.generate(extractPrompt, null, maxTokens = 96) { piece ->
                                    if (_state.value != AssistantState.IDLE) return@generate false
                                    sb.append(piece)
                                    true
                                }
                            }
                            sb.toString()
                        } else {
                            null
                        }
                    }
                } else {
                    null
                },
            )

            val mic = MicStream()
            val earcon = Earcon { suppressMs -> mic.suppressFor(suppressMs) }
            searchCue = { serviceScope.launch(Dispatchers.IO) { runCatching { earcon.playSearching() } } }
            // Reading a sentence aloud has natural pauses ("Jarvis, ... what time is it?"): a request's 0.75 s end-of-speech
            // gap cut recordings off after "Jarvis,", so training recordings wait for 1.5 s of silence.
            val enrollSegmenter = SpeechSegmenter(
                vad = turnVadInstance,
                trailingSilenceMs = 1500,
                shortTrailingSilenceMs = 1500,
                noSpeechTimeoutMs = 6000,
                minSpeechFrames = 3,
            )
            val turnController = TurnController(
                segmenter = SpeechSegmenter(
                    vad = turnVadInstance,
                    trailingSilenceMs = 750,
                    shortTrailingSilenceMs = 380,
                    noSpeechTimeoutMs = 4500,
                    minSpeechFrames = 3,
                ),
                live = live,
                accurate = accurate,
                brain = brain,
                speaker = ttsSpeaker,
                preferStreaming = false,
                setState = { next ->
                    updateState(next)
                    updateNotification("Jarvis: ${next.name}")
                },
                onTranscript = { t ->
                    android.util.Log.i("JarvisTurn", "HEARD: \"$t\"")
                    _transcript.value = t
                    chatLog(this@JarvisService).addUser(t)
                },
                onReplyChunk = { r ->
                    android.util.Log.i("JarvisTurn", "SAID: \"$r\"")
                    _reply.value = r
                    chatLog(this@JarvisService).addReplyChunk(r)
                },
                // No "Did you say ...?" round-trip: the streaming model disagrees with the accurate one on most correct
                // transcripts, so it asked about good ones. Brain's confidence check handles unsure answers instead.
                arbiter = TranscriptArbiter(confirmWerThreshold = 2f),
                correctionsProvider = { mind.listActiveCorrectionsMap() },
                onTranscriptionDetail = { liveRaw, accurateRaw, _ ->
                    lastLiveRaw = liveRaw
                    lastAccurateRaw = accurateRaw
                },
                // Write the episode just before the brain answers so an abrupt kill leaves a trace.
                onRespondStart = { chosen ->
                    openEpisodeId?.let { prev ->
                        runCatching { mind.completeEpisode(prev, reply = "", toolName = null, toolArgsJson = null, echoed = false, outcomeOk = false) }
                    }
                    openEpisodeId = runCatching {
                        mind.beginEpisode(System.currentTimeMillis(), lastLiveRaw, lastAccurateRaw, chosen)
                    }.getOrNull()
                },
                onUtterance = { samples, text ->
                    learnInBackground { it.learnText(text) }
                    if (saveVoiceSamplesEnabled(this@JarvisService)) {
                        launch(Dispatchers.IO) {
                            voiceSamples().save(samples, text, VoiceSampleStore.SOURCE_TURN)
                        }
                    }
                },
                onRespondInterrupted = { _ ->
                    openEpisodeId?.let { id ->
                        openEpisodeId = null
                        runCatching { mind.completeEpisode(id, reply = "", toolName = null, toolArgsJson = null, echoed = false, outcomeOk = false) }
                    }
                },
                onCorrectionConfirmed = { heard, meant ->
                    learnInBackground { it.learnCorrection(heard, meant) }
                    mind.learnCorrection(heard, meant)
                    mind.learnVocab(meant, source = "confirmed")
                    if (regenerateHotwords(mind, hotwordsWriter, hotwordsFile) > 0) {
                        runCatching { live.updateHotwords(hotwordsFile) }
                    }
                    serviceScope.launch(Dispatchers.IO) { publishMindState(mind, selfModel) }
                },
                onSpeechActivity = {
                    lastInteractionMs = System.currentTimeMillis()
                },
                textFixer = { text ->
                    val fix = psm.correct(text)
                    if (fix.changes.isNotEmpty()) {
                        android.util.Log.i("JarvisVoice", "personal fix: \"$text\" -> \"${fix.text}\" ${fix.changes}")
                    }
                    fix.text
                },
            )

            // One-time: learn from read-aloud recordings made before the personal model existed.
            val psmBackfilled = File(filesDir, "personal_speech.backfilled")
            if (!psmBackfilled.exists() && accurate != null) {
                serviceScope.launch(Dispatchers.IO) {
                    delay(20_000)
                    var n = 0
                    for (s in voiceSamples().list().filter { it.source == VoiceSampleStore.SOURCE_ENROLL }) {
                        while (_state.value != AssistantState.IDLE) delay(500)
                        val audio = runCatching { VoiceSampleStore.readWav(File(voiceSamples().dir, s.wav)) }.getOrNull() ?: continue
                        val heard = runCatching { accurate.transcribe(audio) }.getOrNull() ?: continue
                        psm.learnPair(heard, s.text)
                        n++
                    }
                    psm.save()
                    runCatching { psmBackfilled.writeText("$n\n") }
                    android.util.Log.i("JarvisVoice", "personal speech model learned from $n recordings: ${psm.vocabularySize} words, ${psm.confusionCount} confusions")
                }
            }

            val textMutex = kotlinx.coroutines.sync.Mutex()
            textInputHandler = { text, speak ->
                serviceScope.launch {
                    textMutex.withLock {
                        // Let a voice turn in progress finish first; new voice turns wait while this one runs.
                        while (_state.value != AssistantState.IDLE) delay(100)
                        textTurnActive.set(true)
                        try {
                            android.util.Log.i("JarvisTurn", "TYPED: \"$text\"")
                            chatLog(this@JarvisService).addUser(text, typed = true)
                            learnInBackground { it.learnText(text) }
                            _transcript.value = text
                            lastInteractionMs = System.currentTimeMillis()
                            updateState(AssistantState.THINKING)
                            ttsSpeaker.clearStop()
                            brain.respond(text) { sentence ->
                                android.util.Log.i("JarvisTurn", "SAID: \"$sentence\"")
                                _searching.value = false
                                _reply.value = sentence
                                chatLog(this@JarvisService).addReplyChunk(sentence)
                                if (speak) {
                                    updateState(AssistantState.SPEAKING)
                                    ttsSpeaker.speak(sentence)
                                }
                            }
                        } catch (t: Throwable) {
                            if (t is kotlinx.coroutines.CancellationException) throw t
                            android.util.Log.e("JarvisTurn", "text turn failed", t)
                            chatLog(this@JarvisService).addReplyChunk("Sorry, something went wrong.")
                        } finally {
                            textTurnActive.set(false)
                            updateState(AssistantState.IDLE)
                        }
                    }
                }
            }

            val workingStateStore = WorkingStateStore(mind)
            saveWorkingStateFn = {
                val (turns, lastTurnMs) = brain.snapshotHistory()
                val pending = turnController.snapshotPending()
                workingStateStore.save(
                    WorkingState(
                        savedAtMs = System.currentTimeMillis(),
                        history = turns,
                        lastTurnMs = lastTurnMs,
                        pendingHeard = pending?.first,
                        pendingCandidate = pending?.second,
                        initiativeMutedUntilMs = initiativeEngine.mutedUntilMs(),
                        openEpisodeId = openEpisodeId,
                    ),
                )
            }
            workingStateStore.load()?.let { ws ->
                brain.restoreHistory(ws.history, ws.lastTurnMs)
                val heard = ws.pendingHeard
                val candidate = ws.pendingCandidate
                if (heard != null && candidate != null) turnController.restorePending(heard, candidate)
                initiativeEngine.restoreMute(ws.initiativeMutedUntilMs)
            }

            // Save the model memory 3 s after the assistant returns to IDLE with no new turn.
            launch(Dispatchers.IO) {
                _state.collectLatest { s ->
                    if (s == AssistantState.IDLE) {
                        // Every return to IDLE checkpoints working state (pending confirmations, cleared state).
                        runCatching { saveWorkingStateFn?.invoke() }
                        delay(KV_IDLE_SAVE_DELAY_MS)
                        withContext(Dispatchers.Default) { saveKv() }
                    }
                }
            }

            val evalGate = OnDeviceEvalGate()
            val brainEvolver = BrainEvolver()

            fun runEvolutionCycle(manual: Boolean): BrainEvolver.EvolutionResult? {
                if (!llmAvailable || !jniEngine.isLoaded) return null
                if (!evolutionRunning.compareAndSet(false, true)) return null
                return try {
                    val snap = selfModel.capture()
                    val isIdle = _state.value == AssistantState.IDLE
                    if (!brainEvolver.canRunOvernight(snap, isIdle = isIdle, manualOverride = manual)) {
                        return null
                    }
                    val (nEmbd, nLayer) = jniEngine.modelGeometry()
                    val prevVersion = mind.listModelVersions().firstOrNull { it.active }
                    val prevVec = prevVersion?.adapterPath?.takeIf { it.isNotBlank() }?.let { rawPath ->
                        val f = File(rawPath).let { if (it.isAbsolute) it else File(adaptersDir, rawPath) }
                        if (f.isFile) ControlVectorCodec.read(f) else null
                    }
                    val res = brainEvolver.evolveOnce(
                        store = mind,
                        adaptersDir = adaptersDir,
                        baseModelName = paths.llmFileName,
                        nEmbd = nEmbd,
                        nLayer = nLayer,
                        previousAdapterData = prevVec?.data,
                        previousIlStart = prevVec?.ilStart ?: 1,
                        previousIlEnd = prevVec?.ilEnd ?: 1,
                        trainVectorFn = { pos, neg, ilStart, ilEnd ->
                            jniEngine.trainControlVector(pos, neg, ilStart, ilEnd)
                        },
                        applyVectorFn = { data, vecEmbd, ilStart, ilEnd ->
                            val vec = if (data != null && vecEmbd > 0 && data.size >= vecEmbd) {
                                ControlVector(
                                    nEmbd = vecEmbd,
                                    nLayersMinus1 = data.size / vecEmbd,
                                    ilStart = ilStart,
                                    ilEnd = ilEnd,
                                    data = data,
                                )
                            } else {
                                null
                            }
                            expertRouter.setEvolvedControlVector(vec)
                        },
                        evalGate = evalGate,
                        generateFn = { probe ->
                            val sampling = expertRouter.selectAndActivate(probe.prompt, jniEngine)
                            val sb = StringBuilder()
                            jniEngine.generate(
                                prompt = prompts.prompt(emptyList(), probe.prompt),
                                grammar = prompts.grammar(),
                                maxTokens = 72,
                                sampling = sampling,
                            ) { piece ->
                                if (_state.value != AssistantState.IDLE) return@generate false
                                sb.append(piece)
                                true
                            }
                            sb.toString().trim()
                        },
                        isInterrupted = { _state.value != AssistantState.IDLE },
                    )
                    applyModelVersionToRouter(mind.listModelVersions().firstOrNull { it.active })
                    if (jniEngine.isLoaded && _state.value == AssistantState.IDLE) {
                        runCatching { expertRouter.selectAndActivate("", jniEngine) }
                    }
                    onVersionChanged()
                    android.util.Log.i(
                        "JarvisMind",
                        "Evolution cycle finished (manual=$manual, attempted=${res.attempted}, adopted=${res.adopted}): ${res.reason}",
                    )
                    res
                } finally {
                    evolutionRunning.set(false)
                }
            }

            MindUiBridge.actions = object : MindUiActions {
                override fun undoJournal(id: Long) {
                    serviceScope.launch(Dispatchers.IO) {
                        mind.undoJournalEntry(id)
                        if (regenerateHotwords(mind, hotwordsWriter, hotwordsFile) > 0) {
                            runCatching { live.updateHotwords(hotwordsFile) }
                        }
                        publishMindState(mind, selfModel)
                    }
                }

                override fun deleteFact(id: Long) {
                    serviceScope.launch(Dispatchers.IO) {
                        mind.deleteFact(id)
                        publishMindState(mind, selfModel)
                    }
                }

                override fun deleteVocab(id: Long) {
                    serviceScope.launch(Dispatchers.IO) {
                        mind.deleteVocab(id)
                        regenerateHotwords(mind, hotwordsWriter, hotwordsFile)
                        runCatching { live.updateHotwords(hotwordsFile) }
                        publishMindState(mind, selfModel)
                    }
                }

                override fun deleteCorrection(id: Long) {
                    serviceScope.launch(Dispatchers.IO) {
                        mind.deleteCorrection(id)
                        regenerateHotwords(mind, hotwordsWriter, hotwordsFile)
                        runCatching { live.updateHotwords(hotwordsFile) }
                        publishMindState(mind, selfModel)
                    }
                }

                override fun deleteShortcut(id: Long) {
                    serviceScope.launch(Dispatchers.IO) {
                        mind.deleteShortcut(id)
                        publishMindState(mind, selfModel)
                    }
                }

                override fun activateVersion(id: Long) {
                    serviceScope.launch(Dispatchers.IO) {
                        val ok = mind.activateModelVersion(id)
                        val active = mind.listModelVersions().firstOrNull { it.active }
                        applyModelVersionToRouter(active)
                        if (llmAvailable && jniEngine.isLoaded && _state.value == AssistantState.IDLE) {
                            runCatching { expertRouter.selectAndActivate("", jniEngine) }
                        }
                        onVersionChanged()
                        statusNotice = if (ok && active != null) {
                            "Activated brain v${active.id} (${active.adapterPath?.let { File(it).name } ?: "base"})"
                        } else {
                            "Brain version #$id not found"
                        }
                        publishMindState(mind, selfModel)
                    }
                }

                override fun setLearningPaused(paused: Boolean) {
                    serviceScope.launch(Dispatchers.IO) {
                        mind.setLearningPaused(paused)
                        publishMindState(mind, selfModel)
                    }
                }

                override fun exportJson() {
                    serviceScope.launch(Dispatchers.IO) {
                        val exportFile = File("/sdcard/Jarvis/mind-export.json")
                        val ok = mind.exportToJson(exportFile)
                        statusNotice = if (ok) {
                            "Exported mind snapshot to ${exportFile.absolutePath}"
                        } else {
                            "Failed to export mind snapshot"
                        }
                        publishMindState(mind, selfModel)
                    }
                }

                override fun triggerReflection() {
                    serviceScope.launch(Dispatchers.IO) {
                        SimdAudio.pinCurrentThreadToLittleCores()
                        val report = reflectionWorker.reflectOnce(
                            canRun = true,
                            isInterrupted = { _state.value != AssistantState.IDLE },
                        )
                        if (report.vocabLearned > 0 || report.correctionsLearned > 0) {
                            runCatching { live.updateHotwords(hotwordsFile) }
                        }
                        lastReflectionMs = System.currentTimeMillis()
                        statusNotice = if (report.episodesProcessed == 0) {
                            "No unreflected episodes to consolidate"
                        } else {
                            "Reflected ${report.episodesProcessed} turns (+${report.factsLearned} facts, +${report.vocabLearned} words, +${report.shortcutsPromoted} shortcuts)"
                        }
                        publishMindState(mind, selfModel)
                    }
                }

                override fun triggerEvolution() {
                    serviceScope.launch(Dispatchers.IO) {
                        if (!llmAvailable || !jniEngine.isLoaded) {
                            statusNotice = "Cannot evolve: chat LLM is not loaded"
                            publishMindState(mind, selfModel)
                            return@launch
                        }
                        statusNotice = "Running Stage 4 control-vector evolution..."
                        publishMindState(mind, selfModel)
                        val result = runEvolutionCycle(manual = true)
                        statusNotice = result?.let {
                            if (it.adopted) {
                                "Evolved brain adopted (eval=${"%.1f".format(Locale.US, (it.candidateEval?.evalScore ?: 0f) * 100f)}%)"
                            } else {
                                "Evolution skipped/rolled back: ${it.reason}"
                            }
                        } ?: "Evolution skipped (paused, busy, or thermal guard)"
                        publishMindState(mind, selfModel)
                    }
                }

                override fun refresh() {
                    serviceScope.launch(Dispatchers.IO) {
                        publishMindState(mind, selfModel)
                    }
                }
            }

            launch(Dispatchers.IO) { publishMindState(mind, selfModel) }

            val learnNowRunning = AtomicBoolean(false)
            learnNowFn = {
                if (!learnNowRunning.compareAndSet(false, true)) {
                    true
                } else {
                    serviceScope.launch(Dispatchers.IO) {
                        try {
                            delay(1_500)
                            while (_state.value != AssistantState.IDLE) delay(300)
                            val report = reflectionWorker.reflectOnce(
                                canRun = true,
                                isInterrupted = { _state.value != AssistantState.IDLE },
                            )
                            if (report.vocabLearned > 0 || report.correctionsLearned > 0) {
                                runCatching { live.updateHotwords(hotwordsFile) }
                            }
                            publishMindState(mind, selfModel)
                            val msg = ReflectionWorker.learnNowSummary(report, mind.isLearningPaused())
                            android.util.Log.i("JarvisMind", "learn now: $report")
                            while (_state.value != AssistantState.IDLE) delay(300)
                            if (initiativeSpeaking.compareAndSet(false, true)) {
                                try {
                                    updateState(AssistantState.LISTENING)
                                    updateState(AssistantState.THINKING)
                                    updateState(AssistantState.SPEAKING, msg)
                                    _reply.value = msg
                                    chatLog(this@JarvisService).addReplyChunk(msg)
                                    ttsSpeaker.clearStop()
                                    ttsSpeaker.speak(msg)
                                } finally {
                                    mic.suppressFor(250L)
                                    updateState(AssistantState.IDLE)
                                    initiativeSpeaking.set(false)
                                }
                            }
                        } finally {
                            learnNowRunning.set(false)
                        }
                    }
                    true
                }
            }

            // Background idle reflection + overnight Stage 4 evolution loop pinned to little cores (cpu0..3).
            reflectionJob?.cancel()
            reflectionJob = serviceScope.launch(Dispatchers.IO) {
                SimdAudio.pinCurrentThreadToLittleCores()
                while (isActive) {
                    delay(60_000L)
                    val snap = selfModel.capture()
                    val idleMs = System.currentTimeMillis() - lastInteractionMs
                    val canRun = _state.value == AssistantState.IDLE &&
                        idleMs >= REFLECTION_IDLE_MS &&
                        (snap.charging || snap.batteryPct > 50) &&
                        healthGuard.level() == HealthGuard.Level.NORMAL
                    if (canRun) {
                        val report = reflectionWorker.reflectOnce(
                            canRun = true,
                            isInterrupted = { _state.value != AssistantState.IDLE },
                        )
                        if (report.episodesProcessed > 0) {
                            lastReflectionMs = System.currentTimeMillis()
                            if (report.vocabLearned > 0 || report.correctionsLearned > 0) {
                                runCatching { live.updateHotwords(hotwordsFile) }
                            }
                            publishMindState(mind, selfModel)
                        }
                        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        val lastEvolveDay = mind.getMeta("last_evolution_day")
                        if (llmAvailable && jniEngine.isLoaded &&
                            lastEvolveDay != todayKey &&
                            brainEvolver.canRunOvernight(
                                snapshot = snap,
                                isIdle = (_state.value == AssistantState.IDLE),
                                manualOverride = false,
                            )
                        ) {
                            val evoResult = runEvolutionCycle(manual = false)
                            if (evoResult != null && evoResult.attempted) {
                                mind.setMeta("last_evolution_day", todayKey)
                                publishMindState(mind, selfModel)
                            }
                        }
                    }
                }
            }

            // Background proactive initiative loop (due reminders, thermal/battery alerts, greetings, daily recap).
            initiativeJob?.cancel()
            initiativeJob = serviceScope.launch(Dispatchers.IO) {
                SimdAudio.pinCurrentThreadToLittleCores()
                while (isActive) {
                    delay(30_000L)
                    if (_state.value != AssistantState.IDLE || initiativeSpeaking.get()) {
                        continue
                    }
                    val snap = selfModel.capture()
                    val now = System.currentTimeMillis()
                    val idleMs = now - lastInteractionMs
                    val recentPresence = (now - latestMotionMs) in 0L..90_000L
                    val cutoff = now - 24L * 3600_000L
                    val learningsToday = mind.listJournal(100).count {
                        !it.undone && it.createdAtMs >= cutoff && it.type in LEARNING_TYPES
                    }
                    val action = initiativeEngine.evaluate(
                        InitiativeEngine.InitiativeInput(
                            isIdle = _state.value == AssistantState.IDLE,
                            snapshot = snap,
                            idleMs = idleMs,
                            recentPresenceEvent = recentPresence,
                            newLearningsToday = learningsToday,
                            unexpectedShutdown = recoveryReport.unclean,
                        )
                    ) ?: continue

                    if (initiativeSpeaking.compareAndSet(false, true)) {
                        try {
                            if (_state.value == AssistantState.IDLE) {
                                updateState(AssistantState.LISTENING)
                                updateState(AssistantState.THINKING)
                                updateState(AssistantState.SPEAKING, action.spoken)
                                _reply.value = action.spoken
                                ttsSpeaker.clearStop()
                                ttsSpeaker.speak(action.spoken)
                                lastInteractionMs = System.currentTimeMillis()
                            }
                        } finally {
                            mic.suppressFor(250L)
                            updateState(AssistantState.IDLE)
                            initiativeSpeaking.set(false)
                            publishMindState(mind, selfModel)
                        }
                    }
                }
            }

            updateState(AssistantState.IDLE, startStatus)

            var turnChannel: Channel<FloatArray>? = null
            var turnJob: Job? = null
            // The last ~320 ms of idle audio. The wake word is only reported once it has been fully decoded, and the
            // mic is muted during the chime, so without this the user's first word ("what's ...") was cut off.
            val preRoll = ArrayDeque<FloatArray>()

            try {
                mic.frames().collect { frame ->
                    val rms = computeRms(frame)
                    _micLevel.value = rms
                    diagFrames++
                    if (rms > diagMaxRms) diagMaxRms = rms
                    if (diagFrames >= 32) {
                        android.util.Log.d(
                            "JarvisWake",
                            "1s: maxLevel=%.3f vadSpeechFrames=%d state=%s".format(diagMaxRms, diagVadFrames, _state.value),
                        )
                        diagFrames = 0; diagVadFrames = 0; diagMaxRms = 0f
                    }

                    if (_state.value == AssistantState.SPEAKING || initiativeSpeaking.get() || textTurnActive.get()) {
                        return@collect
                    }

                    val activeTurn = turnChannel
                    if (activeTurn != null && turnJob?.isActive == true) {
                        if (_state.value == AssistantState.LISTENING) {
                            activeTurn.trySend(frame)
                        }
                        return@collect
                    }

                    preRoll.addLast(frame)
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
                    val enrollText = enrollLabel.getAndSet(null)
                    if (enrollText != null) {
                        android.util.Log.i("JarvisVoice", "ENROLL listening: $enrollText")
                        earcon.playWake()
                        updateState(AssistantState.LISTENING)
                        val ch = Channel<FloatArray>(Channel.UNLIMITED)
                        preRoll.clear()
                        turnChannel = ch
                        turnJob = launch {
                            try {
                                val audio = turnController.captureUtterance(ch.receiveAsFlow(), enrollSegmenter)
                                val saved = audio?.let {
                                    voiceSamples().save(it, enrollText, VoiceSampleStore.SOURCE_ENROLL)
                                }
                                android.util.Log.i("JarvisVoice", "ENROLL ${saved?.wav ?: "no speech"}: $enrollText")
                                if (audio != null && saved != null && accurate != null) {
                                    val heardEnroll = runCatching { accurate.transcribe(audio) }.getOrNull()
                                    if (heardEnroll != null) learnInBackground { it.learnPair(heardEnroll, enrollText) }
                                }
                                publishEnrollResult(enrollText, saved != null)
                            } finally {
                                updateState(AssistantState.IDLE)
                                ch.close()
                                mic.suppressFor(250L)
                                gatedWake.reset()
                            }
                        }
                        return@collect
                    }
                    val followUp = followUpRequested.getAndSet(false)
                    val woken = followUp || manualWakeRequested.getAndSet(false) || gatedWake.accept(frame)
                    if (woken) {
                        lastInteractionMs = System.currentTimeMillis()
                        android.util.Log.i("JarvisWake", "WAKE detected")
                        if (healthGuard.shouldUnloadChatModel() && jniEngine.isLoaded) {
                            jniEngine.unload()
                        } else if (!healthGuard.shouldUnloadChatModel() && !jniEngine.isLoaded && missingLlm.isEmpty()) {
                            ttsSpeaker.speak("One moment.")
                            runCatching { jniEngine.load(llmFile, contextTokens = 2048) }
                        }

                        if (followUp) {
                            // Conversation continues: no chime, and no pre-roll (it holds the tail of Jarvis's reply).
                            android.util.Log.i("JarvisWake", "FOLLOW-UP listening")
                            preRoll.clear()
                        } else {
                            earcon.playWake()
                        }
                        updateState(AssistantState.LISTENING)
                        updateNotification("Jarvis: LISTENING")
                        val ch = Channel<FloatArray>(Channel.UNLIMITED)
                        for (f in preRoll) ch.trySend(f)
                        preRoll.clear()
                        turnChannel = ch
                        turnJob = launch {
                            try {
                                turnController.runTurn(ch.receiveAsFlow(), followUp = followUp)
                                if (turnController.hasPendingConfirmation) {
                                    manualWakeRequested.set(true)
                                } else if (turnController.invitesFollowUp && followUpEnabled(this@JarvisService)) {
                                    followUpRequested.set(true)
                                }
                            } finally {
                                ch.close()
                                mic.suppressFor(250L)
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

    private fun publishMindState(mind: SqliteMindStore, selfModel: SelfModel) {
        runCatching {
            val snap = selfModel.capture()
            MindUiBridge.publish(
                MindUiState(
                    learningPaused = snap.learningPaused,
                    journal = mind.listJournal(100).map {
                        JournalItemUi(it.id, it.type, it.summary, it.createdAtMs, it.undone)
                    },
                    facts = mind.listActiveFacts(100).map {
                        FactItemUi(it.id, it.text, it.confidence, it.createdAtMs)
                    },
                    vocab = mind.listActiveVocab().map {
                        VocabItemUi(it.id, it.phrase, it.source)
                    },
                    corrections = mind.listActiveCorrections().map {
                        CorrectionItemUi(it.id, it.id, it.heard, it.meant, it.hitCount)
                    },
                    shortcuts = mind.listActiveShortcuts().map {
                        ShortcutItemUi(it.id, it.canonicalUtterance, it.toolName, it.argsJson, it.hitCount)
                    },
                    versions = mind.listModelVersions().map {
                        BrainVersionUi(
                            id = it.id,
                            label = it.baseModel,
                            adapterPath = it.adapterPath,
                            evalScore = it.evalScore,
                            echoRate = it.echoRate,
                            toolAccuracy = it.toolAccuracy,
                            active = it.active,
                        )
                    },
                    resources = ResourceStatusUi(
                        batteryPct = snap.batteryPct,
                        charging = snap.charging,
                        batteryTempC = snap.batteryTempC,
                        maxCpuTempC = snap.maxCpuTempC,
                        healthLevel = snap.healthLevelName,
                        availRamMb = snap.availRamMb,
                        totalRamMb = snap.totalRamMb,
                        littleCpuMhz = snap.littleFreqMhz,
                        bigCpuMhz = snap.bigFreqMhz,
                        activeModel = snap.activeModel,
                        activeExperts = snap.activeExperts,
                        lux = snap.lux,
                        nearProximity = snap.nearProximity,
                        lastReflectionMs = snap.lastReflectionMs,
                        episodeCount = snap.episodeCount,
                        statusNotice = statusNotice,
                    ),
                )
            )
        }
    }

    private fun regenerateHotwords(
        mind: SqliteMindStore,
        writer: HotwordsWriter?,
        outFile: File,
    ): Int {
        if (writer == null) return 0
        val phrases = buildList {
            addAll(mind.listActiveVocab().map { it.phrase })
            addAll(mind.listActiveCorrections().map { it.meant })
        }.distinct()
        return writer.writeHotwords(phrases, outFile)
    }

    private fun parseShortcutToolCall(shortcut: LearnedShortcut): ToolCall? {
        return runCatching {
            val argsMap = LinkedHashMap<String, String>()
            if (shortcut.argsJson.isNotBlank()) {
                val obj = JSONObject(shortcut.argsJson)
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    argsMap[key] = obj.opt(key)?.toString() ?: ""
                }
            }
            ToolCall(shortcut.toolName, argsMap)
        }.getOrNull()
    }

    private fun buildMemoryContext(mind: SqliteMindStore, queryVec: FloatArray): String? {
        // Only clearly related facts: loosely related ones cost the small model ~60 prompt tokens of prefill per turn,
        // break KV reuse of the history (the injected note is not in it) and get blended into unrelated answers.
        val facts = mind.retrieveRelevantFacts(queryVec, topK = 2, minSim = 0.45f)
        // Only a near-identical earlier question: a loosely related old answer (often a guess) was being re-used as fact.
        val ep = mind.retrieveRelevantEpisode(queryVec, minSim = 0.80f)
        if (facts.isEmpty() && ep == null) return null
        val parts = ArrayList<String>()
        for (f in facts) {
            parts.add(f.text)
        }
        if (ep != null && ep.reply.isNotBlank()) {
            parts.add("Prev Q='${ep.chosenTranscript.take(60)}' A='${ep.reply.take(80)}'")
        }
        return parts.joinToString(" | ").take(240)
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
        private const val KV_SAVE_TIMEOUT_MS = 2_000L
        private const val KV_IDLE_SAVE_DELAY_MS = 3_000L
        private const val REFLECTION_IDLE_MS = 10L * 60_000L
        private const val PRE_ROLL_FRAMES = 10 // 10 x 512 samples @16 kHz = 320 ms
        private val LEARNING_TYPES = setOf("fact", "vocab", "correction", "shortcut")

        const val ACTION_STOP_SPEAKING = "com.jarvis.edge.action.STOP_SPEAKING"
        const val ACTION_MANUAL_WAKE = "com.jarvis.edge.action.MANUAL_WAKE"
        const val ACTION_TEXT_INPUT = "com.jarvis.edge.action.TEXT_INPUT"
        const val ACTION_ENROLL = "com.jarvis.edge.action.ENROLL"
        private const val PREF_SAVE_VOICE = "save_voice_samples"
        /** Where voice recordings for training the recogniser are kept (pulled by tools/voice-train). */
        const val VOICE_SAMPLES_DIR = "/sdcard/Jarvis/voice_samples"

        @Volatile private var voiceSampleStore: VoiceSampleStore? = null

        fun voiceSamples(): VoiceSampleStore = voiceSampleStore ?: synchronized(this) {
            voiceSampleStore ?: VoiceSampleStore(File(VOICE_SAMPLES_DIR)).also { voiceSampleStore = it }
        }

        /** Keep each spoken request's audio (labelled with what was heard) for training; on by default. */
        fun saveVoiceSamplesEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_SAVE_VOICE, true)

        fun setSaveVoiceSamplesEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_SAVE_VOICE, enabled).apply()
        }

        /** Outcome of the last voice-training recording; [seq] changes on every attempt. */
        data class EnrollResult(val text: String, val saved: Boolean, val seq: Long)

        private val _enrollResult = MutableStateFlow<EnrollResult?>(null)
        val enrollResult: StateFlow<EnrollResult?> = _enrollResult.asStateFlow()
        private val enrollSeq = java.util.concurrent.atomic.AtomicLong(0)

        private fun publishEnrollResult(text: String, saved: Boolean) {
            _enrollResult.value = EnrollResult(text, saved, enrollSeq.incrementAndGet())
        }

        /** Voice training: Jarvis chimes, records the user reading [text], and saves it as a labelled sample. */
        fun requestEnroll(context: Context, text: String) {
            context.startService(
                Intent(context, JarvisService::class.java).apply {
                    action = ACTION_ENROLL
                    putExtra(EXTRA_TEXT, text)
                },
            )
        }
        const val EXTRA_TEXT = "text"
        const val EXTRA_SPEAK = "speak"
        private const val PREFS = "jarvis_prefs"
        private const val PREF_FOLLOW_UP = "follow_up"

        @Volatile private var chatLogInstance: ChatLog? = null

        /** The conversation history shared by the service (which writes it) and the chat screen (which shows it). */
        fun chatLog(context: Context): ChatLog = chatLogInstance ?: synchronized(this) {
            chatLogInstance ?: ChatLog(File(context.applicationContext.filesDir, "chat_history.jsonl")).also { chatLogInstance = it }
        }

        fun followUpEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_FOLLOW_UP, true)

        fun setFollowUpEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_FOLLOW_UP, enabled).apply()
        }

        /** Text mode: answers [text] like a spoken request; [speak] also reads the reply aloud. */
        fun submitText(context: Context, text: String, speak: Boolean) {
            context.startService(
                Intent(context, JarvisService::class.java).apply {
                    action = ACTION_TEXT_INPUT
                    putExtra(EXTRA_TEXT, text)
                    putExtra(EXTRA_SPEAK, speak)
                },
            )
        }

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var latestLux: Float = 100f

        @Volatile
        var latestNearProximity: Boolean = false

        @Volatile
        var latestMotionMs: Long = 0L

        private val _state = MutableStateFlow(AssistantState.IDLE)
        val state: StateFlow<AssistantState> = _state.asStateFlow()

        private val _message = MutableStateFlow("Starting...")
        val message: StateFlow<String> = _message.asStateFlow()

        private val _transcript = MutableStateFlow("")
        val transcript: StateFlow<String> = _transcript.asStateFlow()

        private val _reply = MutableStateFlow("")
        val reply: StateFlow<String> = _reply.asStateFlow()

        private val _searching = MutableStateFlow(false)
        /** A web lookup is running: the HUD orb and the chat screen animate (cleared when Jarvis speaks or goes idle). */
        val searching: StateFlow<Boolean> = _searching.asStateFlow()

        private val _micLevel = MutableStateFlow(0f)
        val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

        private val _activeExpert = MutableStateFlow("CONVERSATION")
        val activeExpert: StateFlow<String> = _activeExpert.asStateFlow()

        private val _screenFillWhite = MutableStateFlow(false)
        val screenFillWhite: StateFlow<Boolean> = _screenFillWhite.asStateFlow()

        private val _brightnessOverride = MutableStateFlow<Float?>(null)
        val brightnessOverride: StateFlow<Float?> = _brightnessOverride.asStateFlow()

        fun updateState(next: AssistantState, msg: String? = null) {
            if (_state.value.canMoveTo(next)) {
                _state.value = next
            }
            if (next == AssistantState.SPEAKING || next == AssistantState.IDLE || next == AssistantState.ERROR) {
                _searching.value = false
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
