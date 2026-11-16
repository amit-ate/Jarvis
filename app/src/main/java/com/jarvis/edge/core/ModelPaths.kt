package com.jarvis.edge.core

import java.io.File

class MissingModelException(val relativePath: String) :
    IllegalStateException("Missing model file: $relativePath")

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
        "vlm/SmolVLM-500M-Instruct-Q8_0.gguf",
        "vlm/mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
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
    }
}
