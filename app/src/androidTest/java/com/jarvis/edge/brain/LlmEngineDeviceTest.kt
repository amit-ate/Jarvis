package com.jarvis.edge.brain

import com.jarvis.edge.core.ModelPaths
import com.jarvis.edge.tools.Tool
import com.jarvis.edge.tools.ToolResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LlmEngineDeviceTest {

    private val modelFile = File(ModelPaths.DEFAULT_ROOT, ModelPaths(File(ModelPaths.DEFAULT_ROOT)).llmRelative())

    private fun testTools(): List<Tool> = listOf(
        object : Tool {
            override val name = "torch"
            override val description = "Turn flashlight on or off"
            override val params = mapOf("state" to listOf("on", "off"))
            override suspend fun run(args: Map<String, String>) = ToolResult("ok", true)
        },
        object : Tool {
            override val name = "remember"
            override val description = "Save note"
            override val params = mapOf("text" to null)
            override suspend fun run(args: Map<String, String>) = ToolResult("ok", true)
        },
    )

    @Test
    fun secondCallReusesSystemPrompt() {
        val engine = LlamaJniEngine()
        engine.load(modelFile, contextTokens = 2048)
        try {
            val builder = PromptBuilder(testTools(), "chatml")
            engine.generate(builder.prompt(emptyList(), "hello"), builder.grammar()) { true }
            val stats = engine.generate(
                builder.prompt(emptyList(), "what is two plus two"),
                builder.grammar(),
            ) { true }
            assertTrue("Expected cachedTokens >= 100, got ${stats.cachedTokens}", stats.cachedTokens >= 100)
            assertTrue("Expected firstTokenMs <= 1500, got ${stats.firstTokenMs}", stats.firstTokenMs <= 1500)
        } finally {
            engine.unload()
        }
    }

    @Test
    fun outputAlwaysParsesUnderGrammar() {
        val engine = LlamaJniEngine()
        engine.load(modelFile, contextTokens = 2048)
        try {
            val builder = PromptBuilder(testTools(), "chatml")
            val prompts = listOf(
                "Hello there",
                "Turn on the flashlight",
                "Who discovered gravity",
                "Remember my bike lock is 1234",
                "Tell me a one line joke",
                "Turn off the torch",
                "Why is the sky blue",
                "What is the capital of Japan",
                "How many minutes are in an hour",
                "Explain photosynthesis briefly",
                "Say good morning",
                "Save a note that milk is in the fridge",
                "What does HTTP stand for",
                "Name a planet with rings",
                "Turn the light on",
                "Who wrote Hamlet",
                "What is seven times eight",
                "Turn off the flash",
                "Say goodbye",
                "Give me one safety tip",
            )
            val grammar = builder.grammar()
            for (q in prompts) {
                val sb = StringBuilder()
                engine.generate(builder.prompt(emptyList(), q), grammar, maxTokens = 96) { piece ->
                    sb.append(piece)
                    true
                }
                val raw = sb.toString().trim()
                val parsed = parseModelOutput(raw)
                assertTrue(
                    "Expected grammar-constrained SAY: or Call JSON for '$q', got '$raw'",
                    raw.startsWith("SAY:", ignoreCase = true) || parsed is ModelOutput.Call,
                )
            }
        } finally {
            engine.unload()
        }
    }

    @Test
    fun stopsWhenCallbackReturnsFalse() {
        val engine = LlamaJniEngine()
        engine.load(modelFile, contextTokens = 2048)
        try {
            val builder = PromptBuilder(testTools(), "chatml")
            var tokens = 0
            val stats = engine.generate(
                builder.prompt(emptyList(), "Tell me a long story about space"),
                builder.grammar(),
                maxTokens = 160,
            ) {
                tokens++
                tokens < 3
            }
            assertFalse(stats.outputTokens > 4)
        } finally {
            engine.unload()
        }
    }
}
