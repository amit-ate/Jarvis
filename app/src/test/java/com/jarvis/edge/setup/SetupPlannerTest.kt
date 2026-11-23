package com.jarvis.edge.setup

import com.jarvis.edge.core.ModelPaths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SetupPlannerTest {

    private val manifest = ModelManifest.parse(File("src/main/assets/models/manifest.json").readText())

    private fun planner(root: File) = SetupPlanner(root, manifest)

    private fun File.put(rel: String): File = File(this, rel).apply {
        parentFile?.mkdirs()
        writeBytes(byteArrayOf(1, 2, 3))
    }

    /** Every speech model file, with a one-entry folder for the espeak data. */
    private fun fillSpeech(root: File) {
        ModelPaths(root).speechRequired().forEach { rel ->
            if (rel == "tts/espeak-ng-data") root.put("$rel/en_dict") else root.put(rel)
        }
    }

    @Test
    fun `phone with speech models and gemma needs no setup`(@TempDir root: File) {
        fillSpeech(root)
        root.put("llm/${ModelPaths.DEFAULT_LLM_MODEL}")
        assertFalse(planner(root).needsSetup())
    }

    @Test
    fun `empty root needs setup and pending is the whole manifest in order`(@TempDir root: File) {
        val planner = planner(root)
        assertTrue(planner.needsSetup())
        assertEquals(manifest.items.map { it.id }, planner.pending().map { it.id })
    }

    @Test
    fun `missing chat model alone triggers setup`(@TempDir root: File) {
        fillSpeech(root)
        assertTrue(planner(root).needsSetup())
    }

    @Test
    fun `qwen 1_5b alone counts as a chat model`(@TempDir root: File) {
        fillSpeech(root)
        root.put("llm/${ModelPaths.QWEN_15B_MODEL}")
        assertFalse(planner(root).needsSetup())
    }

    @Test
    fun `RUNNING marker forces setup even when all core files exist`(@TempDir root: File) {
        fillSpeech(root)
        root.put("llm/${ModelPaths.DEFAULT_LLM_MODEL}")
        val planner = planner(root)
        planner.writeMarker(SetupMarker.RUNNING)
        assertEquals("running", File(root, ".setup-state").readText())
        assertEquals(SetupMarker.RUNNING, planner.marker())
        assertTrue(planner.needsSetup())
        planner.writeMarker(SetupMarker.NONE)
        assertEquals(SetupMarker.NONE, planner.marker())
        assertFalse(File(root, ".setup-state").exists())
        assertFalse(planner.needsSetup())
    }

    @Test
    fun `EXTRAS_PENDING marker does not force setup`(@TempDir root: File) {
        fillSpeech(root)
        root.put("llm/${ModelPaths.DEFAULT_LLM_MODEL}")
        val planner = planner(root)
        planner.writeMarker(SetupMarker.EXTRAS_PENDING)
        assertEquals(SetupMarker.EXTRAS_PENDING, planner.marker())
        assertFalse(planner.needsSetup())
    }

    @Test
    fun `an empty destination folder counts as missing`(@TempDir root: File) {
        File(root, "asr-live").mkdirs()
        val pending = planner(root).pending().map { it.id }
        assertTrue("asr-live" in pending)
    }

    @Test
    fun `a present file with a different size than the manifest is not re-fetched`(@TempDir root: File) {
        root.put("vad/silero_vad.onnx")
        val planner = planner(root)
        val vad = manifest.items.single { it.id == "vad" }
        assertTrue(planner.isPresent(vad))
        assertFalse(planner.pending().any { it.id == "vad" })
    }
}
