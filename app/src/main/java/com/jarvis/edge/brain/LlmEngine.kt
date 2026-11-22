package com.jarvis.edge.brain

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class GenStats(
    val promptTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    val firstTokenMs: Long,
    val tokensPerSecond: Float,
)

data class SamplingConfig(
    val temperature: Float = 0.45f,
    val repeatPenalty: Float = 1.18f,
    val freqPenalty: Float = 0.15f,
    val presencePenalty: Float = 0.10f,
    val minP: Float = 0.05f,
    val topP: Float = 0.90f,
    val penalizeUserTokens: Int = 0,
)

interface LlmEngine {
    fun load(modelFile: File, contextTokens: Int = 2048)
    fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int = 160,
        onToken: (String) -> Boolean,
    ): GenStats

    fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int = 160,
        sampling: SamplingConfig,
        onToken: (String) -> Boolean,
    ): GenStats = generate(prompt, grammar, maxTokens, onToken)

    fun loadLoraAdapter(adapterFile: File): Long = 0L

    fun setActiveLoraAdapters(adapters: List<Pair<Long, Float>>): Boolean = false

    fun applyControlVector(
        data: FloatArray?,
        nEmbd: Int = 0,
        ilStart: Int = 1,
        ilEnd: Int = 1,
    ): Boolean = false

    fun modelGeometry(): Pair<Int, Int> = 0 to 0

    fun trainControlVector(
        positivePrompts: List<String>,
        negativePrompts: List<String>,
        ilStart: Int,
        ilEnd: Int,
    ): FloatArray = FloatArray(0)

    fun saveState(file: File): Boolean = false

    fun loadState(file: File): Boolean = false

    fun unload()
    val isLoaded: Boolean

    /** Log-probability the model gave the token just passed to onToken (NaN when unknown). Read it inside onToken. */
    val lastTokenLogProb: Float get() = Float.NaN
}

fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}

class LlamaJniEngine : LlmEngine {
    @Volatile
    private var handle: Long = 0L

    private val loadedLoras = LinkedHashSet<Long>()

    /** One lock for every native call that touches the llama context; generate holds it for the whole decode. */
    private val lock = ReentrantLock()

    override val isLoaded: Boolean
        get() = handle != 0L

    /** Where per-phone thread tuning results live; set by the service before [load]. */
    @Volatile
    var tuningStore: ThreadTuningStore? = null

    /** Cache key of the loaded model's tuning (model, CPU code variant, core layout). */
    @Volatile
    private var tuneKey: String? = null

    /** Thread counts in use (null: the native defaults) and an unfinished real-answer trial, if any. */
    private var threads: ThreadChoice? = null
    private var trial: GenerationTrial? = null

    /** The processor as the native layer sees it (null if the report could not be read). */
    val cpuInfo: CpuInfo? by lazy { runCatching { CpuInfo.parse(nativeCpuInfo()) }.getOrNull() }

    /** Result of timing one thread count. */
    sealed interface BenchResult {
        data class Timed(val timing: ThreadTiming, val elapsedMs: Long) : BenchResult
        /** The user needed the model; try this count again later. */
        object Cancelled : BenchResult
        /** Failed or too slow for its time budget. */
        data class TooSlow(val elapsedMs: Long) : BenchResult
    }

    override val lastTokenLogProb: Float
        get() = handle.let { if (it == 0L) Float.NaN else nativeLastTokenLogProb(it) }

    override fun load(modelFile: File, contextTokens: Int) {
        lock.withLock {
            if (handle != 0L) {
                unload()
            }
            require(modelFile.exists() && modelFile.length() > 0L) {
                "Model file missing: ${modelFile.absolutePath}"
            }
            val loaded = nativeLoad(modelFile.absolutePath, contextTokens)
            check(loaded != 0L) { "Failed to load GGUF model: ${modelFile.absolutePath}" }
            handle = loaded
            val key = cpuInfo?.let { ThreadTuner.cacheKey(modelFile, it) }
            tuneKey = key
            val cached = key?.let { k -> runCatching { tuningStore?.get(k) }.getOrNull() }
            threads = null
            trial = null
            if (cached != null && nativeSetThreads(loaded, cached.generate, cached.prefill)) useChoice(cached)
        }
    }

    private fun useChoice(choice: ThreadChoice) {
        threads = choice
        trial = if (choice.trialGenerate > 0) GenerationTrial(choice.generate, choice.trialGenerate) else null
    }

    /** During a trial: switch to the count that needs answers next. Returns the generation count in use. */
    private fun prepareTrial(h: Long): Int? {
        val t = trial ?: return null
        val current = threads ?: return null
        val want = t.next()
        if (want != current.generate && nativeSetThreads(h, want, current.prefill)) {
            threads = current.copy(generate = want)
        }
        return threads?.generate
    }

    /** During a trial: record this answer's speed and settle once both counts have enough answers. */
    private fun recordTrial(h: Long, used: Int, stats: GenStats) {
        val t = trial ?: return
        if (stats.outputTokens >= 6) t.record(used, stats.tokensPerSecond.toDouble())
        val winner = t.decided() ?: return
        val current = threads ?: return
        if (winner != current.generate) nativeSetThreads(h, winner, current.prefill)
        val settled = ThreadChoice(winner, current.prefill)
        threads = settled
        trial = null
        val key = tuneKey
        val store = tuningStore
        if (key != null && store != null) runCatching { store.put(key, settled) }
        runCatching { android.util.Log.i("JarvisLlm", "generation threads settled on $winner from real answers (${t.summary()})") }
    }

    /** True when the loaded model has never been tuned on this phone. */
    fun needsThreadTuning(): Boolean {
        val key = tuneKey ?: return false
        val store = tuningStore ?: return false
        return isLoaded && runCatching { store.get(key) }.getOrNull() == null
    }

    /** Times [threads] threads on a throw-away context; gives up after [budgetMs] or when [generate] is called. */
    fun benchThreads(threads: Int, budgetMs: Long): BenchResult = lock.withLock {
        val h = handle
        if (h == 0L) return BenchResult.TooSlow(0)
        val t0 = System.nanoTime()
        val raw = nativeBenchThreads(h, threads, budgetMs)
        val elapsed = (System.nanoTime() - t0) / 1_000_000
        when {
            raw.size == 1 && raw[0] < 0 -> BenchResult.Cancelled
            raw.size == 2 && raw[0] > 0 && raw[1] > 0 -> BenchResult.Timed(ThreadTiming(threads, raw[0], raw[1]), elapsed)
            else -> BenchResult.TooSlow(elapsed)
        }
    }

    /** Applies and remembers tuned thread counts for the loaded model. */
    fun applyThreads(choice: ThreadChoice): Boolean = lock.withLock {
        val h = handle
        if (h == 0L || !nativeSetThreads(h, choice.generate, choice.prefill)) return false
        useChoice(choice)
        val key = tuneKey
        val store = tuningStore
        if (key != null && store != null) runCatching { store.put(key, choice) }
        true
    }

    override fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onToken: (String) -> Boolean,
    ): GenStats = generate(
        prompt = prompt,
        grammar = grammar,
        maxTokens = maxTokens,
        sampling = SamplingConfig(),
        onToken = onToken,
    )

    override fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        sampling: SamplingConfig,
        onToken: (String) -> Boolean,
    ): GenStats {
        nativeCancelBench() // a thread-timing run gives the model back at once
        return lock.withLock {
        val currentHandle = handle
        check(currentHandle != 0L) { "LlmEngine is not loaded" }
        val trialThreads = prepareTrial(currentHandle)

        val raw = nativeGenerateWithSampling(
            handle = currentHandle,
            prompt = prompt,
            grammar = grammar,
            maxTokens = maxTokens,
            temperature = sampling.temperature,
            repeatPenalty = sampling.repeatPenalty,
            freqPenalty = sampling.freqPenalty,
            presencePenalty = sampling.presencePenalty,
            minP = sampling.minP,
            topP = sampling.topP,
            penalizeUserTokens = sampling.penalizeUserTokens,
            callback = TokenCallback { piece -> onToken(piece) },
        )
        parseGenStats(raw).also { s ->
            if (trialThreads != null) recordTrial(currentHandle, trialThreads, s)
            runCatching {
                android.util.Log.i(
                    "JarvisLlm",
                    "prompt ${s.promptTokens} tok (${s.cachedTokens} reused), first token ${s.firstTokenMs} ms, " +
                        "${s.outputTokens} tok at ${"%.1f".format(s.tokensPerSecond)} tok/s",
                )
            }
        }
        }
    }

    override fun loadLoraAdapter(adapterFile: File): Long {
        lock.withLock {
            val h = handle
            if (h == 0L || !adapterFile.isFile || adapterFile.length() <= 0L) {
                return 0L
            }
            val loraHandle = nativeLoadLora(h, adapterFile.absolutePath)
            if (loraHandle != 0L) {
                loadedLoras.add(loraHandle)
            }
            return loraHandle
        }
    }

    override fun setActiveLoraAdapters(adapters: List<Pair<Long, Float>>): Boolean {
        lock.withLock {
            val h = handle
            if (h == 0L) return false
            val valid = adapters.filter { it.first != 0L }
            val handles = LongArray(valid.size) { valid[it].first }
            val scales = FloatArray(valid.size) { valid[it].second }
            return nativeSetLoraAdapters(h, handles, scales) == 0
        }
    }

    override fun applyControlVector(
        data: FloatArray?,
        nEmbd: Int,
        ilStart: Int,
        ilEnd: Int,
    ): Boolean {
        lock.withLock {
            val h = handle
            if (h == 0L) return false
            return nativeApplyControlVector(h, data, nEmbd, ilStart, ilEnd) == 0
        }
    }

    override fun modelGeometry(): Pair<Int, Int> {
        lock.withLock {
            val h = handle
            if (h == 0L) return 0 to 0
            val info = nativeModelInfo(h)
            return if (info.size >= 2) info[0] to info[1] else 0 to 0
        }
    }

    override fun trainControlVector(
        positivePrompts: List<String>,
        negativePrompts: List<String>,
        ilStart: Int,
        ilEnd: Int,
    ): FloatArray {
        lock.withLock {
            val h = handle
            if (h == 0L || positivePrompts.isEmpty() || negativePrompts.isEmpty()) {
                return FloatArray(0)
            }
            return nativeTrainControlVector(
                handle = h,
                posPrompts = positivePrompts.toTypedArray(),
                negPrompts = negativePrompts.toTypedArray(),
                ilStart = ilStart,
                ilEnd = ilEnd,
            )
        }
    }

    /** Never waits: returns false at once if a decode or other native call holds the context. */
    override fun saveState(file: File): Boolean {
        if (!lock.tryLock()) return false
        try {
            val h = handle
            if (h == 0L) return false
            return nativeSaveState(h, file.absolutePath)
        } finally {
            lock.unlock()
        }
    }

    override fun loadState(file: File): Boolean {
        lock.withLock {
            val h = handle
            if (h == 0L || !file.isFile) return false
            return nativeLoadState(h, file.absolutePath)
        }
    }

    override fun unload() {
        nativeCancelBench()
        lock.withLock {
            val h = handle
            handle = 0L
            if (h != 0L) {
                if (loadedLoras.isNotEmpty()) {
                    nativeSetLoraAdapters(h, LongArray(0), FloatArray(0))
                    for (lora in loadedLoras) {
                        if (lora != 0L) {
                            nativeFreeLora(lora)
                        }
                    }
                    loadedLoras.clear()
                }
                nativeUnload(h)
            }
        }
    }

    private fun parseGenStats(raw: LongArray): GenStats {
        val promptTokens = raw.getOrElse(0) { 0L }.toInt()
        val cachedTokens = raw.getOrElse(1) { 0L }.toInt()
        val outputTokens = raw.getOrElse(2) { 0L }.toInt()
        val firstTokenMs = raw.getOrElse(3) { 0L }
        val genElapsedUs = raw.getOrElse(4) { 0L }
        val tps = if (genElapsedUs > 0L && outputTokens > 0) {
            (outputTokens * 1_000_000.0f) / genElapsedUs.toFloat()
        } else {
            0.0f
        }
        return GenStats(
            promptTokens = promptTokens,
            cachedTokens = cachedTokens,
            outputTokens = outputTokens,
            firstTokenMs = firstTokenMs,
            tokensPerSecond = tps,
        )
    }

    private external fun nativeLoad(modelPath: String, contextTokens: Int): Long
    private external fun nativeLastTokenLogProb(handle: Long): Float
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        callback: TokenCallback,
    ): LongArray

    private external fun nativeGenerateWithSampling(
        handle: Long,
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        temperature: Float,
        repeatPenalty: Float,
        freqPenalty: Float,
        presencePenalty: Float,
        minP: Float,
        topP: Float,
        penalizeUserTokens: Int,
        callback: TokenCallback,
    ): LongArray

    private external fun nativeLoadLora(handle: Long, loraPath: String): Long
    private external fun nativeSetLoraAdapters(
        handle: Long,
        loraHandles: LongArray,
        scales: FloatArray,
    ): Int
    private external fun nativeFreeLora(loraHandle: Long)
    private external fun nativeApplyControlVector(
        handle: Long,
        data: FloatArray?,
        nEmbd: Int,
        ilStart: Int,
        ilEnd: Int,
    ): Int
    private external fun nativeModelInfo(handle: Long): IntArray
    private external fun nativeTrainControlVector(
        handle: Long,
        posPrompts: Array<String>,
        negPrompts: Array<String>,
        ilStart: Int,
        ilEnd: Int,
    ): FloatArray
    private external fun nativeSaveState(handle: Long, path: String): Boolean
    private external fun nativeLoadState(handle: Long, path: String): Boolean
    private external fun nativeUnload(handle: Long)
    private external fun nativeBenchThreads(handle: Long, threads: Int, budgetMs: Long): DoubleArray
    private external fun nativeSetThreads(handle: Long, generate: Int, prefill: Int): Boolean

    companion object {
        init {
            System.loadLibrary("llama_jni")
        }

        fun pinCurrentThreadToBigCores() {
            nativePinCurrentThreadToBigCores()
        }

        @JvmStatic
        external fun nativePinCurrentThreadToBigCores()

        @JvmStatic
        external fun nativeCpuInfo(): String

        @JvmStatic
        external fun nativeCancelBench()

        @JvmStatic
        external fun nativeDescribeImage(
            modelPath: String,
            mmprojPath: String,
            rgbBytes: ByteArray,
            width: Int,
            height: Int,
            question: String,
            maxTokens: Int,
            callback: TokenCallback,
        )
    }
}

interface EmbeddingEngine {
    fun embed(text: String): FloatArray
    val isLoaded: Boolean
    fun unload()
}

class LlamaEmbeddingEngine : EmbeddingEngine {
    @Volatile
    private var handle: Long = 0L

    override val isLoaded: Boolean
        get() = handle != 0L

    fun load(modelFile: File) {
        synchronized(this) {
            if (handle != 0L) {
                unload()
            }
            require(modelFile.exists() && modelFile.length() > 0L) {
                "Embedding model file missing: ${modelFile.absolutePath}"
            }
            val loaded = nativeLoadEmbedder(modelFile.absolutePath)
            check(loaded != 0L) { "Failed to load GGUF embedder: ${modelFile.absolutePath}" }
            handle = loaded
        }
    }

    override fun embed(text: String): FloatArray {
        val currentHandle = handle
        check(currentHandle != 0L) { "EmbeddingEngine is not loaded" }
        return nativeEmbed(currentHandle, text)
    }

    override fun unload() {
        synchronized(this) {
            val h = handle
            handle = 0L
            if (h != 0L) {
                nativeUnloadEmbedder(h)
            }
        }
    }

    private external fun nativeLoadEmbedder(modelPath: String): Long
    private external fun nativeEmbed(embedHandle: Long, text: String): FloatArray
    private external fun nativeUnloadEmbedder(embedHandle: Long)

    companion object {
        init {
            System.loadLibrary("llama_jni")
        }
    }
}
