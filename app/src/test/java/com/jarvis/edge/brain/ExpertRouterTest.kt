package com.jarvis.edge.brain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class ExpertRouterTest {

    private data class CvecCall(
        val data: FloatArray?,
        val nEmbd: Int,
        val ilStart: Int,
        val ilEnd: Int,
    )

    private class TrackingLlmEngine : LlmEngine {
        override var isLoaded: Boolean = true
        val loadedFiles = mutableListOf<String>()
        val activeCalls = mutableListOf<List<Pair<Long, Float>>>()
        val cvecCalls = mutableListOf<CvecCall>()
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

        override fun applyControlVector(
            data: FloatArray?,
            nEmbd: Int,
            ilStart: Int,
            ilEnd: Int,
        ): Boolean {
            cvecCalls.add(CvecCall(data?.copyOf(), nEmbd, ilStart, ilEnd))
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
    fun `switches domain control vectors only on change and blends Stage 4 evolved vector`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val toolVec = ControlVector(
            nEmbd = 2,
            nLayersMinus1 = 3,
            ilStart = 1,
            ilEnd = 2,
            data = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f),
        )
        val convVec = ControlVector(
            nEmbd = 2,
            nLayersMinus1 = 3,
            ilStart = 1,
            ilEnd = 3,
            data = floatArrayOf(0f, 2f, 2f, 0f, 1f, 1f),
        )
        ControlVectorCodec.write(File(dir, "tool_expert.jvec"), toolVec)
        ControlVectorCodec.write(File(dir, "conversation_expert.jvec"), convVec)

        val manifest = File(dir, "experts.json").apply {
            writeText(
                """
                {
                  "version": 1,
                  "experts": [
                    {
                      "domain": "TOOL_USE",
                      "cvec_path": "tool_expert.jvec",
                      "cvec_scale": 0.4,
                      "cvec_layer_start": 1,
                      "cvec_layer_end": 2,
                      "keywords": ["flashlight", "timer"]
                    },
                    {
                      "domain": "CONVERSATION",
                      "cvec_path": "conversation_expert.jvec",
                      "cvec_scale": 0.5,
                      "cvec_layer_start": 1,
                      "cvec_layer_end": 3
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val router = ExpertRouter.fromManifestFile(manifest, dir)
        val engine = TrackingLlmEngine()

        assertEquals("TOOL_USE+cvec", router.activeExpertSummary(ExpertDomain.TOOL_USE))
        assertEquals("REASONING", router.activeExpertSummary(ExpertDomain.REASONING))

        // First TOOL_USE call applies scaled toolVec.
        router.selectAndActivate("Turn on the flashlight", engine)
        assertEquals(1, engine.cvecCalls.size)
        assertArrayEquals(
            floatArrayOf(0.4f, 0f, 0f, 0.4f, 0f, 0f),
            engine.cvecCalls[0].data,
            1e-5f,
        )
        assertEquals(2, engine.cvecCalls[0].nEmbd)
        assertEquals(1, engine.cvecCalls[0].ilStart)
        assertEquals(2, engine.cvecCalls[0].ilEnd)

        // Repeated TOOL_USE call does not re-apply identical signature.
        router.selectAndActivate("Set a timer", engine)
        assertEquals(1, engine.cvecCalls.size)

        // Switching to REASONING (no cvec) clears active control vector once.
        router.selectAndActivate("Explain why the sky is blue", engine)
        assertEquals(2, engine.cvecCalls.size)
        assertNull(engine.cvecCalls[1].data)

        // Repeating REASONING does not redundantly clear again.
        router.selectAndActivate("Calculate 2 plus 2", engine)
        assertEquals(2, engine.cvecCalls.size)

        // Attach Stage 4 evolved control vector and route to CONVERSATION.
        val evolved = ControlVector(
            nEmbd = 2,
            nLayersMinus1 = 3,
            ilStart = 2,
            ilEnd = 3,
            data = floatArrayOf(0f, 0f, 1f, 1f, 2f, -2f),
        )
        router.setEvolvedControlVector(evolved, scale = 0.25f)
        assertEquals("CONVERSATION+cvec+evolved", router.activeExpertSummary(ExpertDomain.CONVERSATION))

        router.selectAndActivate("Hey there friend", engine)
        assertEquals(3, engine.cvecCalls.size)
        // 0.5 * convVec + 0.25 * evolved
        assertArrayEquals(
            floatArrayOf(0f, 1.0f, 1.25f, 0.25f, 1.0f, 0.0f),
            engine.cvecCalls[2].data,
            1e-5f,
        )
        assertEquals(1, engine.cvecCalls[2].ilStart)
        assertEquals(3, engine.cvecCalls[2].ilEnd)

        // Clearing evolved vector updates CONVERSATION back to domain-only cvec.
        router.setEvolvedControlVector(null)
        assertEquals("CONVERSATION+cvec", router.activeExpertSummary(ExpertDomain.CONVERSATION))
        router.selectAndActivate("How are you today?", engine)
        assertEquals(4, engine.cvecCalls.size)
        assertArrayEquals(
            floatArrayOf(0f, 1.0f, 1.0f, 0f, 0.5f, 0.5f),
            engine.cvecCalls[3].data,
            1e-5f,
        )
    }

    @Test
    fun `appliedSignature follows what was applied to the engine and returns to default after empty select`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        ControlVectorCodec.write(
            File(dir, "tool_expert.jvec"),
            ControlVector(2, 3, 1, 2, floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)),
        )
        File(dir, "experts.json").writeText(
            """
            {"version":1,"experts":[{"domain":"TOOL_USE","cvec_path":"tool_expert.jvec","cvec_scale":0.4,
            "cvec_layer_start":1,"cvec_layer_end":2,"keywords":["flashlight"]}]}
            """.trimIndent(),
        )
        val router = ExpertRouter.fromManifestFile(File(dir, "experts.json"), dir)
        val engine = TrackingLlmEngine()

        router.selectAndActivate("", engine)
        val defaultSig = router.appliedSignature(engine)
        router.selectAndActivate("Turn on the flashlight", engine)
        val toolSig = router.appliedSignature(engine)
        assertTrue(defaultSig != toolSig)
        router.selectAndActivate("", engine)
        assertEquals(defaultSig, router.appliedSignature(engine))
    }

    @Test
    fun `steady adapters keep one control vector across domains so the KV cache survives`(@TempDir tempDir: Path) {
        val dir = tempDir.toFile()
        val toolVec = ControlVector(nEmbd = 2, nLayersMinus1 = 3, ilStart = 1, ilEnd = 2, data = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f))
        val convVec = ControlVector(nEmbd = 2, nLayersMinus1 = 3, ilStart = 1, ilEnd = 3, data = floatArrayOf(0f, 2f, 2f, 0f, 1f, 1f))
        ControlVectorCodec.write(File(dir, "tool_expert.jvec"), toolVec)
        ControlVectorCodec.write(File(dir, "conversation_expert.jvec"), convVec)
        val manifest = File(dir, "experts.json").apply {
            writeText(
                """{"version":1,"experts":[
                {"domain":"TOOL_USE","cvec_path":"tool_expert.jvec","cvec_scale":0.4,"cvec_layer_start":1,"cvec_layer_end":2,"temperature":0.2,"keywords":["flashlight"]},
                {"domain":"CONVERSATION","cvec_path":"conversation_expert.jvec","cvec_scale":0.5,"cvec_layer_start":1,"cvec_layer_end":3,"temperature":0.5}]}""",
            )
        }
        val router = ExpertRouter.fromManifestFile(manifest, dir, steadyAdapters = true)
        val engine = TrackingLlmEngine()

        val chat = router.selectAndActivate("Hey there", engine)
        val tool = router.selectAndActivate("Turn on the flashlight", engine)
        val why = router.selectAndActivate("Explain why the sky is blue", engine)
        router.selectAndActivate("", engine)

        assertEquals(1, engine.cvecCalls.size)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 0f, 0.5f, 0.5f), engine.cvecCalls[0].data, 1e-5f)
        // Domains still pick their own sampling.
        assertEquals(0.5f, chat.temperature, 1e-5f)
        assertEquals(0.2f, tool.temperature, 1e-5f)
        assertEquals(0.25f, why.temperature, 1e-5f)
    }
}
