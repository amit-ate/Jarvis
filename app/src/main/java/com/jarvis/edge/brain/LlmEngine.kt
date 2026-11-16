package com.jarvis.edge.brain

import java.io.File

data class GenStats(
    val promptTokens: Int,
    val cachedTokens: Int,
    val outputTokens: Int,
    val firstTokenMs: Long,
    val tokensPerSecond: Float,
)

interface LlmEngine {
    fun load(modelFile: File, contextTokens: Int = 2048)
    fun generate(
        prompt: String,
        grammar: String?,
        maxTokens: Int = 160,
        onToken: (String) -> Boolean,
    ): GenStats
    fun unload()
    val isLoaded: Boolean
}

fun interface TokenCallback {
    fun onToken(piece: String): Boolean
}

class LlamaJniEngine : LlmEngine {
    @Volatile
    private var handle: Long = 0L

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
    ): GenStats {
        val currentHandle = handle
        check(currentHandle != 0L) { "LlmEngine is not loaded" }

        val raw = nativeGenerate(
            handle = currentHandle,
            prompt = prompt,
            grammar = grammar,
            maxTokens = maxTokens,
            callback = TokenCallback { piece -> onToken(piece) },
        )
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

    override fun unload() {
        synchronized(this) {
            val h = handle
            handle = 0L
            if (h != 0L) {
                nativeUnload(h)
            }
        }
    }

    private external fun nativeLoad(modelPath: String, contextTokens: Int): Long
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        callback: TokenCallback,
    ): LongArray
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
