package com.jarvis.edge.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ModelPathsTest {

    @Test
    fun `missing lists absent model files by relative path`(@TempDir tempDir: File) {
        val paths = ModelPaths(tempDir)
        assertTrue("vad/silero_vad.onnx" in paths.missing())
        assertTrue("vlm/SmolVLM-256M-Instruct-Q8_0.gguf" in paths.vlmRequired())
    }

    @Test
    fun `require throws MissingModelException for absent or empty file`(@TempDir tempDir: File) {
        val paths = ModelPaths(tempDir)
        val emptyFile = File(tempDir, "vad/silero_vad.onnx").apply {
            parentFile?.mkdirs()
            createNewFile()
        }
        assertTrue(emptyFile.exists())
        val ex = assertThrows(MissingModelException::class.java) {
            paths.require("vad/silero_vad.onnx")
        }
        assertEquals("vad/silero_vad.onnx", ex.relativePath)
    }

    @Test
    fun `require returns file when present and non-empty`(@TempDir tempDir: File) {
        val paths = ModelPaths(tempDir)
        val f = File(tempDir, "vad/silero_vad.onnx").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        assertEquals(f, paths.require("vad/silero_vad.onnx"))
    }

    @Test
    fun `active model file picks a present model and falls back otherwise`(@TempDir tempDir: File) {
        assertEquals(ModelPaths.DEFAULT_LLM_MODEL, ModelPaths.resolveLlmFileName(tempDir))
        val llm = File(tempDir, "llm").apply { mkdirs() }
        File(llm, "active_model.txt").writeText("gemma.gguf\n")
        assertEquals(ModelPaths.DEFAULT_LLM_MODEL, ModelPaths.resolveLlmFileName(tempDir))
        File(llm, "gemma.gguf").writeBytes(byteArrayOf(1))
        assertEquals("gemma.gguf", ModelPaths.resolveLlmFileName(tempDir))
        File(llm, "active_model.txt").writeText("../evil.gguf")
        assertEquals(ModelPaths.DEFAULT_LLM_MODEL, ModelPaths.resolveLlmFileName(tempDir))
    }

    @Test
    fun `preferred model is used when no active model is named`(@TempDir tempDir: File) {
        val llm = File(tempDir, "llm").apply { mkdirs() }
        File(llm, "google_gemma-3-1b-it-Q4_0.gguf").writeBytes(byteArrayOf(1))
        assertEquals("google_gemma-3-1b-it-Q4_0.gguf", ModelPaths.resolveLlmFileName(tempDir))
    }

    @Test
    fun `accurate recogniser prefers custom, then parakeet, then moonshine`(@TempDir tempDir: File) {
        assertEquals(null, ModelPaths.resolveAccurateAsr(tempDir))
        fun put(rel: String) = File(tempDir, rel).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        put("asr-moonshine/encode.int8.onnx")
        assertEquals(AccurateAsr.Kind.MOONSHINE, ModelPaths.resolveAccurateAsr(tempDir)?.kind)
        listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").forEach { put("asr-parakeet/$it") }
        assertEquals(File(tempDir, "asr-parakeet"), ModelPaths.resolveAccurateAsr(tempDir)?.dir)
        // An incomplete custom model (no joiner) is skipped.
        listOf("encoder.onnx", "decoder.onnx", "tokens.txt").forEach { put("asr-custom/$it") }
        assertEquals(File(tempDir, "asr-parakeet"), ModelPaths.resolveAccurateAsr(tempDir)?.dir)
        put("asr-custom/joiner.onnx")
        assertEquals(File(tempDir, "asr-custom"), ModelPaths.resolveAccurateAsr(tempDir)?.dir)
    }
}
