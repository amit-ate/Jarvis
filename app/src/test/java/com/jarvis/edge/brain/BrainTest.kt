package com.jarvis.edge.brain

import com.jarvis.edge.tools.Tool
import com.jarvis.edge.tools.ToolRegistry
import com.jarvis.edge.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class BrainTest {

    private class FakeLlm(
        private val responder: (callIdx: Int, prompt: String, sampling: SamplingConfig, onToken: (String) -> Boolean) -> Unit,
    ) : LlmEngine {
        override var isLoaded: Boolean = true
        var calls: Int = 0
        val prompts = mutableListOf<String>()
        val samplings = mutableListOf<SamplingConfig>()

        override fun load(modelFile: File, contextTokens: Int) {
            isLoaded = true
        }

        override fun generate(
            prompt: String,
            grammar: String?,
            maxTokens: Int,
            onToken: (String) -> Boolean,
        ): GenStats = generate(prompt, grammar, maxTokens, SamplingConfig(), onToken)

        override fun generate(
            prompt: String,
            grammar: String?,
            maxTokens: Int,
            sampling: SamplingConfig,
            onToken: (String) -> Boolean,
        ): GenStats {
            val idx = calls++
            prompts.add(prompt)
            samplings.add(sampling)
            responder(idx, prompt, sampling, onToken)
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

    private fun restoreBrain(now: Long, fakeLlm: FakeLlm): Brain {
        val tools = listOf(simpleTimeTool())
        return Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            clockMillis = { now },
        )
    }

    @Test
    fun `restored history is used in next prompt`() = runBlocking {
        val now = 10_000_000L
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Ok.") }
        val brain = restoreBrain(now, fakeLlm)
        brain.restoreHistory(listOf(Turn("my dog is Rex", "SAY: Nice.")), now - 10_000L)
        brain.respond("tell me a story") { }
        assertTrue(fakeLlm.prompts.single().contains("my dog is Rex"))
    }

    @Test
    fun `concurrent snapshot and restore and respond do not throw`() {
        val now = 10_000_000L
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Ok.") }
        val brain = restoreBrain(now, fakeLlm)
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val snapshotter = Thread {
            try {
                while (!stop.get()) {
                    brain.snapshotHistory()
                    brain.restoreHistory(listOf(Turn("a", "SAY: b"), Turn("c", "SAY: d")), now)
                }
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        snapshotter.start()
        try {
            runBlocking { repeat(300) { brain.respond("tell me story $it") { } } }
        } finally {
            stop.set(true)
            snapshotter.join()
        }
        assertEquals(null, failure.get())
    }

    @Test
    fun `stale history is not restored`() = runBlocking {
        val now = 10_000_000L
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Ok.") }
        val brain = restoreBrain(now, fakeLlm)
        brain.restoreHistory(listOf(Turn("my dog is Rex", "SAY: Nice.")), now - 3_600_000L)
        brain.respond("tell me a story") { }
        assertFalse(fakeLlm.prompts.single().contains("my dog is Rex"))
    }

    @Test
    fun `fast intent never touches the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, _ ->
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
        val fakeLlm = FakeLlm { _, _, _, onToken ->
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
        val fakeLlm = FakeLlm { _, _, _, onToken ->
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
        val fakeLlm = FakeLlm { idx, _, _, onToken ->
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

    @Test
    fun `echoed first response is never spoken aloud and triggers anti-echo retry`() = runBlocking {
        val fakeLlm = FakeLlm { idx, _, _, onToken ->
            if (idx == 0) {
                onToken("SAY: Why is the ocean salty?")
            } else {
                onToken("SAY: Rivers wash dissolved mineral salts into the sea over millions of years.")
            }
        }
        val episodes = mutableListOf<Triple<String, String, Boolean>>()
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            onEpisodeComplete = { _, reply, _, echoed -> episodes.add(Triple("", reply, echoed)) },
        )
        val spoken = mutableListOf<String>()
        brain.respond("why is the ocean salty") { spoken.add(it) }

        assertEquals(2, fakeLlm.calls)
        assertEquals(
            listOf("Rivers wash dissolved mineral salts into the sea over millions of years."),
            spoken,
        )
        assertEquals(24, fakeLlm.samplings[1].penalizeUserTokens)
        assertTrue(fakeLlm.prompts[1].contains("[Rule: Do NOT repeat the user's words. Answer directly.]"))
        assertEquals(listOf(Triple("", "Rivers wash dissolved mineral salts into the sea over millions of years.", false)), episodes)
    }

    @Test
    fun `double echo on factual question falls back to web_search and skips history`() = runBlocking {
        val searchTool = object : Tool {
            override val name = "web_search"
            override val description = "Search web"
            override val params = mapOf("query" to null)
            override suspend fun run(args: Map<String, String>) =
                ToolResult("Marie Curie won two Nobel Prizes.", ok = true)
        }
        val fakeLlm = FakeLlm { idx, _, _, onToken ->
            if (idx < 2) {
                onToken("SAY: Who won two Nobel Prizes?")
            } else {
                onToken("SAY: Sure, anytime.")
            }
        }
        val episodes = mutableListOf<Boolean>()
        val tools = listOf(simpleTimeTool(), searchTool)
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            onEpisodeComplete = { _, _, _, echoed -> episodes.add(echoed) },
        )
        val spoken = mutableListOf<String>()
        brain.respond("who won two nobel prizes") { spoken.add(it) }
        assertEquals(listOf("Marie Curie won two Nobel Prizes."), spoken)
        assertEquals(listOf(true), episodes)

        // Next turn should NOT contain the echoed turn in conversation history.
        brain.respond("thanks") { }
        assertFalse(fakeLlm.prompts[2].contains("who won two nobel prizes"))
    }

    @Test
    fun `double echo on non-factual prompt speaks safe fallback`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken ->
            onToken("SAY: Tell me something interesting today.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
        )
        val spoken = mutableListOf<String>()
        brain.respond("tell me something interesting today") { spoken.add(it) }
        assertEquals(listOf(Brain.ECHO_FALLBACK_MSG), spoken)
    }

    @Test
    fun `shortcutMatcher, selfStateProvider, and memoryProvider integrate cleanly`() = runBlocking {
        val pingTool = object : Tool {
            override val name = "ping"
            override val description = "Ping"
            override val params: Map<String, List<String>?> = emptyMap()
            override suspend fun run(args: Map<String, String>) = ToolResult("Pong!", ok = true)
        }
        val fakeLlm = FakeLlm { _, _, _, onToken ->
            onToken("SAY: Your bike lock code is 1234.")
        }
        val tools = listOf(pingTool)
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            shortcutMatcher = { text -> if (text == "custom ping") ToolCall("ping", emptyMap()) else null },
            selfStateProvider = { "mood=focused, battery=85%" },
            memoryProvider = { "bike lock is 1234" },
        )

        val shortcutSpoken = mutableListOf<String>()
        brain.respond("custom ping") { shortcutSpoken.add(it) }
        assertEquals(listOf("Pong!"), shortcutSpoken)
        assertEquals(0, fakeLlm.calls)

        val llmSpoken = mutableListOf<String>()
        brain.respond("do you know my lock code") { llmSpoken.add(it) }
        assertEquals(listOf("Your bike lock code is 1234."), llmSpoken)
        assertTrue(fakeLlm.prompts[0].contains("[Self: mood=focused, battery=85%]"))
        assertTrue(fakeLlm.prompts[0].contains("[Memory: bike lock is 1234]"))
    }
}
