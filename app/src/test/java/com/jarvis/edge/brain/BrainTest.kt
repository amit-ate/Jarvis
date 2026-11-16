package com.jarvis.edge.brain

import com.jarvis.edge.tools.Tool
import com.jarvis.edge.tools.ToolRegistry
import com.jarvis.edge.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BrainTest {

    private class FakeLlm(
        private val responder: (callIdx: Int, prompt: String, onToken: (String) -> Boolean) -> Unit,
    ) : LlmEngine {
        override var isLoaded: Boolean = true
        var calls: Int = 0
        val prompts = mutableListOf<String>()

        override fun load(modelFile: File, contextTokens: Int) {
            isLoaded = true
        }

        override fun generate(
            prompt: String,
            grammar: String?,
            maxTokens: Int,
            onToken: (String) -> Boolean,
        ): GenStats {
            val idx = calls++
            prompts.add(prompt)
            responder(idx, prompt, onToken)
            return GenStats(promptTokens = 50, cachedTokens = 40, outputTokens = 10, firstTokenMs = 100, tokensPerSecond = 15f)
        }

        override fun unload() {
            isLoaded = false
        }
    }

    private fun simpleTimeTool() = object : Tool {
        override val name = "get_time"
        override val description = "Get time"
        override val params: Map<String, List<String>?> = emptyMap()
        override suspend fun run(args: Map<String, String>) = ToolResult("It's 3:05 PM.", ok = true)
    }

    @Test
    fun `fast intent never touches the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _ ->
            throw AssertionError("LLM must not be called for fast intent")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
        )
        val spoken = mutableListOf<String>()
        brain.respond("what time is it") { spoken.add(it) }
        assertEquals(listOf("It's 3:05 PM."), spoken)
        assertEquals(0, fakeLlm.calls)
    }

    @Test
    fun `model SAY output is streamed sentence by sentence`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, onToken ->
            onToken("SAY: ")
            onToken("One. ")
            onToken("Two.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
        )
        val spoken = mutableListOf<String>()
        brain.respond("tell me two numbers") { spoken.add(it) }
        assertEquals(listOf("One.", "Two."), spoken)
    }

    @Test
    fun `model tool call runs the tool and speaks its result`() = runBlocking {
        val torchTool = object : Tool {
            override val name = "torch"
            override val description = "Torch"
            override val params = mapOf("state" to listOf("on", "off"))
            override suspend fun run(args: Map<String, String>) =
                ToolResult("Flashlight ${args["state"]}.", ok = true)
        }
        val fakeLlm = FakeLlm { _, _, onToken ->
            onToken("""{"tool":"torch","args":{"state":"off"}}""")
        }
        val tools = listOf(torchTool)
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
        )
        val spoken = mutableListOf<String>()
        brain.respond("please darken the room") { spoken.add(it) }
        assertEquals(listOf("Flashlight off."), spoken)
        assertEquals(1, fakeLlm.calls)
    }

    @Test
    fun `tool with feedToModel triggers exactly one second model pass`() = runBlocking {
        val recallTool = object : Tool {
            override val name = "recall"
            override val description = "Search notes"
            override val params = mapOf("query" to null)
            override suspend fun run(args: Map<String, String>) = ToolResult(
                spoken = "wifi password is on the fridge",
                ok = true,
                feedToModel = "- wifi password is on the fridge",
            )
        }
        val fakeLlm = FakeLlm { idx, _, onToken ->
            if (idx == 0) {
                onToken("""{"tool":"recall","args":{"query":"wifi"}}""")
            } else {
                onToken("SAY: Your wifi password is on the fridge.")
            }
        }
        val tools = listOf(recallTool)
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
        )
        val spoken = mutableListOf<String>()
        brain.respond("where is the wifi password") { spoken.add(it) }
        assertEquals(2, fakeLlm.calls)
        assertEquals(listOf("Your wifi password is on the fridge."), spoken)
        assertTrue(fakeLlm.prompts[1].contains("wifi password is on the fridge"))
    }

    @Test
    fun `brain works with no model loaded`() = runBlocking {
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = null,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
        )
        val fastSpoken = mutableListOf<String>()
        brain.respond("what time is it") { fastSpoken.add(it) }
        assertEquals(listOf("It's 3:05 PM."), fastSpoken)

        val openSpoken = mutableListOf<String>()
        brain.respond("why is the ocean salty") { openSpoken.add(it) }
        assertEquals(
            listOf("My language model isn't loaded, so I can only handle basic commands."),
            openSpoken,
        )
    }
}
