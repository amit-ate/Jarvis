package com.jarvis.edge.bench

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.edge.audio.SimdAudio
import com.jarvis.edge.brain.LlamaJniEngine
import com.jarvis.edge.brain.PromptBuilder
import com.jarvis.edge.core.ModelPaths
import com.jarvis.edge.tools.Tool
import com.jarvis.edge.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

class SimdAndPerformanceDeviceTest {

    companion object {
        private const val TAG = "SimdBench"

        private fun makeTool(
            name: String,
            desc: String,
            params: Map<String, List<String>?> = emptyMap(),
        ): Tool = object : Tool {
            override val name = name
            override val description = desc
            override val params = params
            override suspend fun run(args: Map<String, String>) = ToolResult("ok", true)
        }
    }

    @Test
    fun simdAudioBenchmarkProducesIdenticalResultsAndMeasuresSpeedup() {
        val frameSize = 512
        val gain = 3.0f
        val pcm = ShortArray(frameSize) { i ->
            ((i * 12345) % 65536 - 32768).toShort()
        }

        val simdOut = FloatArray(frameSize)
        val simdRms = SimdAudio.processPcm(pcm, 0, frameSize, simdOut, gain)

        // Compute Kotlin scalar baseline
        val scalarOut = FloatArray(frameSize)
        var scalarSumSq = 0.0
        val scale = gain / 32768.0f
        for (i in 0 until frameSize) {
            val f = (pcm[i] * scale).coerceIn(-1.0f, 1.0f)
            scalarOut[i] = f
            scalarSumSq += (f * f)
        }
        val scalarRms = sqrt(scalarSumSq / frameSize).toFloat()

        // 1. Verify correctness (numerical fidelity)
        assertEquals("RMS mismatch between SIMD and scalar", scalarRms, simdRms, 1e-4f)
        for (i in 0 until frameSize) {
            val diff = abs(scalarOut[i] - simdOut[i])
            assertTrue("Sample $i mismatch: scalar=${scalarOut[i]} simd=${simdOut[i]}", diff < 1e-4f)
        }

        // 2. Measure performance over 20,000 frames (10.24 million audio samples)
        val iterations = 20_000
        val timings = SimdAudio.nativeBenchmark(iterations, frameSize)
        val scalarNs = timings[0]
        val simdNs = timings[1]

        val scalarMs = scalarNs / 1_000_000.0
        val simdMs = simdNs / 1_000_000.0
        val speedup = scalarMs / simdMs
        val samplesPerSec = (iterations.toDouble() * frameSize) / (simdNs / 1e9)

        Log.i(TAG, "=== AUDIO NEON SIMD BENCHMARK ($iterations frames, 512 samples/frame) ===")
        Log.i(TAG, "Scalar C++ Total: ${"%.2f".format(scalarMs)} ms (${"%.2f".format(scalarMs * 1000 / iterations)} µs/frame)")
        Log.i(TAG, "NEON SIMD Total:   ${"%.2f".format(simdMs)} ms (${"%.2f".format(simdMs * 1000 / iterations)} µs/frame)")
        Log.i(TAG, "SIMD Speedup:      ${"%.2f".format(speedup)}x")
        Log.i(TAG, "SIMD Throughput:   ${"%.2f".format(samplesPerSec / 1e6)} Million samples/sec")

        assertTrue("Expected SIMD speedup >= 1.5x, got $speedup", speedup >= 1.5)
        assertTrue("Expected frame processing < 5 µs, got ${simdMs * 1000 / iterations} µs", (simdMs * 1000 / iterations) < 5.0)
    }

    @Test
    fun grammarCachingEliminatesRecompilationLatency() {
        val root = File(ModelPaths.DEFAULT_ROOT)
        val modelFile = File(root, "llm/qwen2.5-0.5b-instruct-q4_0.gguf")
        if (!modelFile.exists()) {
            Log.w(TAG, "LLM model missing at $modelFile, skipping")
            return
        }

        val engine = LlamaJniEngine()
        engine.load(modelFile, contextTokens = 2048)
        try {
            val tools = listOf(
                makeTool("torch", "Control torch", mapOf("state" to listOf("on", "off"))),
                makeTool("set_volume", "Volume", mapOf("percent" to null)),
                makeTool("get_weather", "Weather", mapOf("location" to null)),
                makeTool("web_search", "Search web", mapOf("query" to null)),
                makeTool("get_time", "Get time", emptyMap()),
            )
            val builder = PromptBuilder(tools, "chatml")
            val grammar = builder.grammar()

            // Run 1: Uncached (initial compile)
            val sb1 = StringBuilder()
            val stats1 = engine.generate(
                builder.prompt(emptyList(), "Turn on the flashlight"),
                grammar,
                maxTokens = 16,
            ) { piece ->
                sb1.append(piece)
                true
            }

            // Run 2: Cached grammar
            val sb2 = StringBuilder()
            val stats2 = engine.generate(
                builder.prompt(emptyList(), "Turn off the flashlight"),
                grammar,
                maxTokens = 16,
            ) { piece ->
                sb2.append(piece)
                true
            }

            Log.i(TAG, "=== GBNF GRAMMAR CACHING BENCHMARK ===")
            Log.i(TAG, "Run 1 (initial grammar compile): firstTokenMs=${stats1.firstTokenMs} ms, cachedTokens=${stats1.cachedTokens}")
            Log.i(TAG, "Run 2 (cached grammar clone):     firstTokenMs=${stats2.firstTokenMs} ms, cachedTokens=${stats2.cachedTokens}")
            Log.i(TAG, "Output 1: ${sb1.toString().trim()}")
            Log.i(TAG, "Output 2: ${sb2.toString().trim()}")

            assertTrue("Expected output 1 to contain torch call", sb1.contains("torch"))
            assertTrue("Expected output 2 to contain torch call", sb2.contains("torch"))
            assertTrue("Expected cached run firstTokenMs <= 1500ms", stats2.firstTokenMs <= 1500)
        } finally {
            engine.unload()
        }
    }
}
