package com.jarvis.edge.brain

import java.io.File

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

    fun unload()
    val isLoaded: Boolean
}

fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}

class LlamaJniEngine : LlmEngine {
    @Volatile
    private var handle: Long = 0L

    private val loadedLoras = LinkedHashSet<Long>()

    override val isLoaded: Boolean
        get() = handle != 0L

    override fun load(modelFile: File, contextTokens: Int) {
        synchronized(this) {
            if (handle != 0L) {
                unload()
            }
            require(modelFile.exists() && modelFile.length() > 0L) {
                "Model file missing: ${modelFile.absolutePath}"
            }
            val loaded = nativeLoad(modelFile.absolutePath, contextTokens)
            check(loaded != 0L) { "Failed to load GGUF model: ${modelFile.absolutePath}" }
            handle = loaded
        }
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
        val currentHandle = handle
        check(currentHandle != 0L) { "LlmEngine is not loaded" }

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
        return parseGenStats(raw)
    }

    override fun loadLoraAdapter(adapterFile: File): Long {
        synchronized(this) {
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
        synchronized(this) {
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
        synchronized(this) {
            val h = handle
            if (h == 0L) return false
            return nativeApplyControlVector(h, data, nEmbd, ilStart, ilEnd) == 0
        }
    }

    override fun modelGeometry(): Pair<Int, Int> {
        synchronized(this) {
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
        synchronized(this) {
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

    override fun unload() {
        synchronized(this) {
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
    private external fun nativeUnload(handle: Long)

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

/**
 * Pure-Kotlin deterministic hashed char-3gram + word L2-normalized embedder.
 * Used as a fast zero-native-dependency fallback or unit-test embedding backend.
 */
class HashEmbeddingEngine(private val dim: Int = 128) : EmbeddingEngine {
    init {
        require(dim > 0) { "Embedding dimension must be positive, got $dim" }
    }

    override val isLoaded: Boolean = true

    override fun embed(text: String): FloatArray {
        val vec = FloatArray(dim)
        val cleaned = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (cleaned.isEmpty()) return vec

        val words = cleaned.split(' ')
        for (word in words) {
            if (word.isEmpty()) continue
            addFeature(vec, "w:$word", 2.0f)
            val padded = " $word "
            for (i in 0..(padded.length - 3)) {
                addFeature(vec, "g:${padded.substring(i, i + 3)}", 1.0f)
            }
        }

        var normSq = 0.0
        for (v in vec) {
            normSq += v * v
        }
        if (normSq > 1e-12) {
            val invNorm = (1.0 / kotlin.math.sqrt(normSq)).toFloat()
            for (i in vec.indices) {
                vec[i] *= invNorm
            }
        }
        return vec
    }

    override fun unload() = Unit

    private fun addFeature(vec: FloatArray, key: String, weight: Float) {
        val h1 = fnv1a32(key)
        val h2 = fnv1a32("$key#sign")
        val idx = (h1 and 0x7FFF_FFFF) % dim
        val sign = if ((h2 and 1) == 0) 1.0f else -1.0f
        vec[idx] += sign * weight
    }

    private fun fnv1a32(s: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (ch in s) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return hash
    }
}
