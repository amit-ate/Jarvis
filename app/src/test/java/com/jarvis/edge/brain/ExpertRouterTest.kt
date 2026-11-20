package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExpertRouterTest {

    private class TrackingLlmEngine : LlmEngine {
        override var isLoaded: Boolean = true
        val loadedFiles = mutableListOf<String>()
        val activeCalls = mutableListOf<List<Pair<Long, Float>>>()
        private var nextHandle = 100L

        override fun load(modelFile: File, contextTokens: Int) = Unit
        override fun generate(prompt: String, grammar: String?, maxTokens: Int, onToken: (String) -> Boolean): GenStats =
            GenStats(0, 0, 0, 0, 0f)
        override fun unload() = Unit

        override fun loadLoraAdapter(adapterFile: File): Long {
            loadedFiles.add(adapterFile.name)
            return nextHandle++
        }

        override fun setActiveLoraAdapters(adapters: List<Pair<Long, Float>>): Boolean {
            activeCalls.add(adapters)
            return true
        }
    }

    @Test
    fun `classifies domains cleanly`() {
        val router = ExpertRouter()
        assertEquals(ExpertDomain.KNOWLEDGE, router.classify("Who discovered gravity?"))
        assertEquals(ExpertDomain.MEMORY, router.classify("Remember my gate code is 4242"))
        assertEquals(ExpertDomain.VISION, router.classify("What am I holding in front of the camera?"))
        assertEquals(ExpertDomain.TOOL_USE, router.classify("Turn on the flashlight"))
        assertEquals(ExpertDomain.REASONING, router.classify("Calculate and compare these numbers"))
        assertEquals(ExpertDomain.CONVERSATION, router.classify("Hey there, how are you doing?"))
    }

    @Test
    fun `fromManifestFile parses experts json and lazily loads existing lora`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val reasoningLora = File(dir, "reasoning.gguf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val manifest = File(dir, "experts.json").apply {
            writeText(
                """
                {
                  "REASONING": {
                    "lora": "${reasoningLora.name}",
                    "scale": 0.85,
                    "temperature": 0.2,
                    "repeat_penalty": 1.12,
                    "keywords": ["prove", "derive"]
                  }
                }
                """.trimIndent(),
            )
        }

        val router = ExpertRouter.fromManifestFile(manifest)
        val engine = TrackingLlmEngine()

        val cfg1 = router.selectAndActivate("Please derive this formula", engine)
        assertEquals(0.2f, cfg1.temperature, 1e-4f)
        assertEquals(1.12f, cfg1.repeatPenalty, 1e-4f)
        assertEquals(listOf("reasoning.gguf"), engine.loadedFiles)
        assertEquals(listOf(listOf(100L to 0.85f)), engine.activeCalls)

        // Repeating same domain reuses cached LoRA handle without re-loading or clearing KV cache.
        router.selectAndActivate("Prove this theorem", engine)
        assertEquals(1, engine.loadedFiles.size)
        assertEquals(1, engine.activeCalls.size)

        // Switching to a domain without LoRA clears active adapters.
        val cfg2 = router.selectAndActivate("Good morning friend", engine)
        assertEquals(0.55f, cfg2.temperature, 1e-4f)
        assertEquals(listOf(listOf(100L to 0.85f), emptyList()), engine.activeCalls)
    }

    @Test
    fun `fromManifestFile handles missing or corrupt file gracefully`(@TempDir tempDir: Path) {
        val missing = File(tempDir.toFile(), "missing.json")
        val routerMissing = ExpertRouter.fromManifestFile(missing)
        assertEquals(ExpertDomain.CONVERSATION, routerMissing.classify("hello"))

        val corrupt = File(tempDir.toFile(), "corrupt.json").apply { writeText("{not-json") }
        val routerCorrupt = ExpertRouter.fromManifestFile(corrupt)
        assertEquals(ExpertDomain.KNOWLEDGE, routerCorrupt.classify("Who is Ada Lovelace?"))
    }

    @Test
    fun `HashEmbeddingEngine produces deterministic normalized vectors`() {
        val embedder = HashEmbeddingEngine(dim = 64)
        assertTrue(embedder.isLoaded)
        val a1 = embedder.embed("my wifi password is on the fridge")
        val a2 = embedder.embed("my wifi password is on the fridge")
        val b = embedder.embed("turn on the living room lights")
        assertEquals(64, a1.size)
        assertEquals(a1.toList(), a2.toList())

        val normA = kotlin.math.sqrt(a1.sumOf { (it * it).toDouble() })
        assertTrue(kotlin.math.abs(normA - 1.0) < 1e-4)

        val simSame = a1.indices.sumOf { (a1[it] * a2[it]).toDouble() }
        val simDiff = a1.indices.sumOf { (a1[it] * b[it]).toDouble() }
        assertTrue(simSame > simDiff)
    }
}
