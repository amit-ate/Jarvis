package com.jarvis.edge.core

import java.io.File

class MissingModelException(val relativePath: String) :
    IllegalStateException("Missing model file: $relativePath")

/** The accurate (whole-utterance) recogniser to load and where its files are. */
data class AccurateAsr(val kind: Kind, val dir: File) {
    enum class Kind { TRANSDUCER, MOONSHINE }
}

class ModelPaths(
    val root: File,
    val llmFileName: String = DEFAULT_LLM_MODEL,
) {
    fun require(relative: String): File {
        val file = File(root, relative)
        val valid = if (file.isDirectory) {
            file.list()?.isNotEmpty() == true
        } else {
            file.isFile && file.length() > 0L
        }
        if (!valid) {
            throw MissingModelException(relative)
        }
        return file
    }

    fun speechRequired(): List<String> = listOf(
        "vad/silero_vad.onnx",
        "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        "kws/tokens.txt",
        "kws/keywords.txt",
        "asr-zipformer/encoder-epoch-99-avg-1.int8.onnx",
        "asr-zipformer/decoder-epoch-99-avg-1.int8.onnx",
        "asr-zipformer/joiner-epoch-99-avg-1.int8.onnx",
        "asr-zipformer/tokens.txt",
        "tts/en_GB-alan-medium.onnx",
        "tts/tokens.txt",
        "tts/espeak-ng-data",
    )

    fun llmRelative(): String = "llm/$llmFileName"

    fun vlmRequired(): List<String> = listOf(
        "vlm/SmolVLM-256M-Instruct-Q8_0.gguf",
        "vlm/mmproj-SmolVLM-256M-Instruct-Q8_0.gguf",
    )

    fun allRequired(): List<String> = speechRequired() + llmRelative() + vlmRequired()

    fun missingSpeech(): List<String> = missingFrom(speechRequired())

    fun missingLlm(): List<String> = missingFrom(listOf(llmRelative()))

    fun missing(): List<String> = missingFrom(allRequired())

    private fun missingFrom(relatives: List<String>): List<String> =
        relatives.filter { rel ->
            runCatching { require(rel) }.isFailure
        }

    companion object {
        const val DEFAULT_ROOT = "/sdcard/Jarvis/models"
        const val DEFAULT_LLM_MODEL = "qwen2.5-0.5b-instruct-q4_0.gguf"
        /** Optional one-line file naming the chat model to load from llm/ (lets a bigger model be swapped in without a rebuild). */
        const val ACTIVE_MODEL_FILE = "llm/active_model.txt"

        /**
         * Chat models in order of preference, best first. Gemma 3 1B won the on-phone comparison (2026-11-21): 19/20
         * on harder questions vs 16/20 for Qwen2.5-0.5B, first word in ~1.5-3 s once warm; Qwen2.5-1.5B answered as
         * well but took 4-14 s to start, too slow for voice.
         */
        val PREFERRED_LLM_MODELS = listOf("google_gemma-3-1b-it-Q4_0.gguf", DEFAULT_LLM_MODEL)

        /**
         * Accurate recognisers in order of preference: a model fine-tuned on the user's voice (tools/voice-train), then
         * Parakeet TDT 110M, then the original Moonshine tiny.
         */
        val ACCURATE_ASR_DIRS = listOf("asr-custom", "asr-parakeet")

        fun resolveAccurateAsr(root: File, skip: Set<String> = emptySet()): AccurateAsr? {
            fun has(dir: File, base: String) =
                File(dir, "$base.int8.onnx").isFile || File(dir, "$base.onnx").isFile
            for (name in ACCURATE_ASR_DIRS) {
                if (name in skip) continue
                val dir = File(root, name)
                if (has(dir, "encoder") && has(dir, "decoder") && has(dir, "joiner") && File(dir, "tokens.txt").isFile) {
                    return AccurateAsr(AccurateAsr.Kind.TRANSDUCER, dir)
                }
            }
            val moonshine = File(root, "asr-moonshine")
            return if ("asr-moonshine" !in skip && File(moonshine, "encode.int8.onnx").isFile) AccurateAsr(AccurateAsr.Kind.MOONSHINE, moonshine) else null
        }

        /** The chat model named in [ACTIVE_MODEL_FILE] if present, else the first [PREFERRED_LLM_MODELS] on disk. */
        fun resolveLlmFileName(root: File): String {
            fun present(name: String) = File(root, "llm/$name").let { it.isFile && it.length() > 0L }
            val named = runCatching { File(root, ACTIVE_MODEL_FILE).readText().trim() }.getOrNull()
                ?.takeIf { it.isNotEmpty() && !it.contains('/') }
            if (named != null && present(named)) return named
            return PREFERRED_LLM_MODELS.firstOrNull(::present) ?: DEFAULT_LLM_MODEL
        }
    }
}
