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
}
