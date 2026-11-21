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
        /** Log-probability reported for each emitted piece (NaN = unknown, the default). */
        private val logProbFor: (String) -> Float = { Float.NaN },
        private val responder: (callIdx: Int, prompt: String, sampling: SamplingConfig, onToken: (String) -> Boolean) -> Unit,
    ) : LlmEngine {
        private var currentLp = Float.NaN
        override val lastTokenLogProb: Float get() = currentLp
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
            responder(idx, prompt, sampling) { piece -> currentLp = logProbFor(piece); onToken(piece) }
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
    fun `no-model early return still completes the episode`() = runBlocking {
        val tools = listOf(simpleTimeTool())
        val completed = mutableListOf<String>()
        val brain = Brain(
            fast = FastIntents,
            llm = null,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            onEpisodeComplete = { _, reply, _, echoed -> completed.add("$reply|$echoed") },
        )
        brain.respond("why is the ocean salty") { }
        assertEquals(listOf("My language model isn't loaded, so I can only handle basic commands.|false"), completed)
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
        assertFalse(fakeLlm.prompts[0].contains("[Phone status:"))
        assertTrue(fakeLlm.prompts[0].contains("[Memory: bike lock is 1234]"))
    }

    @Test
    fun `asking own age with no memory says it does not know and never calls the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, _ ->
            throw AssertionError("LLM must not be called for an unknown personal attribute")
        }
        val tools = listOf(simpleTimeTool())
        val episodes = mutableListOf<String>()
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            memoryProvider = { null },
            onEpisodeComplete = { _, reply, _, _ -> episodes.add(reply) },
        )
        val spoken = mutableListOf<String>()
        brain.respond("What's my age?") { spoken.add(it) }
        val expected = "I don't know your age yet. Tell me and I'll remember it."
        assertEquals(listOf(expected), spoken)
        assertEquals(0, fakeLlm.calls)
        assertEquals(listOf(expected), episodes)
    }

    @Test
    fun `asking own age with memory present calls the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: You are 30 years old.") }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            memoryProvider = { "User is 30 years old" },
        )
        val spoken = mutableListOf<String>()
        brain.respond("What's my age?") { spoken.add(it) }
        assertEquals(1, fakeLlm.calls)
        assertEquals(listOf("You are 30 years old."), spoken)
        assertTrue(fakeLlm.prompts[0].contains("[Memory: User is 30 years old]"))
    }

    @Test
    fun `phone status is injected only for device questions`() = runBlocking {
        val fakeLlm = FakeLlm { idx, _, _, onToken ->
            onToken(if (idx == 0) "SAY: Sure." else "SAY: Your phone is warm.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            selfStateProvider = { "Self: bat=85%" },
        )
        brain.respond("tell me a joke") { }
        brain.respond("is my phone running hot") { } // "how is my battery" is a FastIntents tool call now
        assertEquals(2, fakeLlm.calls)
        assertFalse(fakeLlm.prompts[0].contains("[Phone status:"))
        assertTrue(fakeLlm.prompts[1].contains("[Phone status: bat=85%]"))
        assertFalse(fakeLlm.prompts[1].contains("Self:"))
    }

    private class MapFacts : PersonalFactStore {
        val map = HashMap<String, String>()
        override fun remember(attribute: String, value: String) { map[attribute] = value }
        override fun recall(attribute: String): String? = map[attribute]
    }

    @Test
    fun `stating then asking a personal fact is remembered and answered without the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, _ -> throw AssertionError("LLM must not be called") }
        val tools = listOf(simpleTimeTool())
        val facts = MapFacts()
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            personalFacts = facts,
        )
        val spoken = mutableListOf<String>()
        brain.respond("My age is 41.") { spoken.add(it) }
        brain.respond("What's my age?") { spoken.add(it) }
        assertEquals("41", facts.map["age"])
        assertEquals(listOf("Got it, your age is 41. I'll remember that.", "Your age is 41."), spoken)
    }

    @Test
    fun `fast tool sentence is spoken as is and the model is never called`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, _ -> throw AssertionError("LLM must not be called") }
        val helloTool = object : Tool {
            override val name = "hello"
            override val description = "Hello"
            override val params: Map<String, List<String>?> = emptyMap()
            override suspend fun run(args: Map<String, String>) =
                ToolResult("Hello there.", ok = true, feedToModel = "x")
        }
        val tools = listOf(helloTool)
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            shortcutMatcher = { text -> if (text == "custom hello") ToolCall("hello", emptyMap()) else null },
        )
        val spoken = mutableListOf<String>()
        brain.respond("custom hello") { spoken.add(it) }
        assertEquals(listOf("Hello there."), spoken)
        assertEquals(0, fakeLlm.calls)
    }

    @Test
    fun `failed web search is not spoken and the model answers with the no-internet rule`() = runBlocking {
        val searchTool = object : Tool {
            override val name = "web_search"
            override val description = "Search web"
            override val params = mapOf("query" to null)
            override suspend fun run(args: Map<String, String>) =
                ToolResult("Sorry, I couldn't find an online answer for ${args["query"]}.", ok = false)
        }
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: I believe that is a politician.") }
        val tools = listOf(simpleTimeTool(), searchTool)
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
        )
        val spoken = mutableListOf<String>()
        brain.respond("who is the president of france") { spoken.add(it) }
        assertEquals(1, fakeLlm.calls)
        assertTrue(fakeLlm.prompts[0].contains("No internet."))
        assertEquals(listOf("I believe that is a politician."), spoken)
    }

    @Test
    fun `reply repeating an earlier turn is retried and the new answer is spoken`() = runBlocking {
        val fakeLlm = FakeLlm { idx, _, _, onToken ->
            if (idx == 2) onToken("SAY: Something new.") else onToken("SAY: Paris is the capital.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
        )
        val first = mutableListOf<String>()
        brain.respond("tell me a joke") { first.add(it) }
        assertEquals(listOf("Paris is the capital."), first)

        val second = mutableListOf<String>()
        brain.respond("tell me something about dogs") { second.add(it) }
        assertEquals(listOf("Something new."), second)
        assertEquals(3, fakeLlm.calls)
    }

    @Test
    fun `personal fact reply is not copied into the next model prompt`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Sure.") }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents,
            llm = fakeLlm,
            prompts = PromptBuilder(tools),
            tools = ToolRegistry(tools),
            personalFacts = MapFacts(),
        )
        brain.respond("My age is 41.") { }
        brain.respond("tell me a joke") { }
        assertEquals(1, fakeLlm.calls)
        assertFalse(fakeLlm.prompts[0].contains("Got it, your age is 41"))
    }

    private class StatementFacts : PersonalFactStore {
        val statements = mutableListOf<String>()
        override fun remember(attribute: String, value: String) {}
        override fun recall(attribute: String): String? = null
        override fun rememberStatement(statement: String) { statements.add(statement) }
        override fun forgetStatement(statement: String) { statements.remove(statement) }
    }

    private fun lp(p: Double) = kotlin.math.ln(p).toFloat()

    @Test
    fun `low-confidence answer to a factual question is withheld`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { if (it.contains("Jazmin")) lp(0.004) else lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: Raul's sister is ")
            onToken("Jazmin.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("Who is Raul Garnish sister?") { out.add(it) }
        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("I'm not sure about that"), out[0])
    }

    @Test
    fun `unsure answer after unsure hearing asks the user to repeat`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.01) }) { _, _, _, onToken ->
            onToken("SAY: Her name")
            onToken(" is Jazmin.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("Who is Raul Garnish sister?", heardConfidence = 0.3f) { out.add(it) }
        assertEquals(listOf("I'm not sure I heard that right. Could you say it again?"), out)
    }

    @Test
    fun `confident answer to a factual question is spoken`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: Shakespeare wrote ")
            onToken("it.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("Who wrote Hamlet?") { out.add(it) }
        assertEquals("Shakespeare wrote it.", out.joinToString(" "))
    }

    @Test
    fun `rambling low-confidence later sentence is cut`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { if (it.contains("Zork")) lp(0.01) else lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: Sure thing.")
            onToken(" Zork")
            onToken(" qux blorp.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("tell me a joke") { out.add(it) }
        assertEquals(listOf("Sure thing."), out)
    }

    @Test
    fun `a fact the user teaches is remembered without the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Yes, that's right.") }
        val facts = StatementFacts()
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools), personalFacts = facts)
        val out = mutableListOf<String>()
        brain.respond("As I know, Rao Ghani's sister name is Priyanka.") { out.add(it) }
        assertEquals(listOf("Thanks, I'll remember that Rao Ghani's sister is Priyanka."), out)
        assertEquals(listOf("Rao Ghani's sister is Priyanka"), facts.statements)
        assertEquals(0, fakeLlm.calls)
    }

    @Test
    fun `correcting a misheard name fixes the taught fact and learns the correction`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Okay.") }
        val facts = StatementFacts()
        val learned = mutableListOf<Pair<String, String>>()
        val tools = listOf(simpleTimeTool())
        val brain = Brain(
            fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools),
            personalFacts = facts, onEntityCorrected = { h, m -> learned.add(h to m) },
        )
        brain.respond("As I know, Rao Ghani's sister name is Priyanka.") { }
        val out = mutableListOf<String>()
        brain.respond("It is Rahul Gandhi's.") { out.add(it) }
        assertEquals(listOf("Rao Ghani" to "Rahul Gandhi"), learned)
        assertEquals(listOf("Rahul Gandhi's sister is Priyanka"), facts.statements)
        assertEquals(listOf("Got it, Rahul Gandhi.", "Thanks, I'll remember that Rahul Gandhi's sister is Priyanka."), out)
    }
}
