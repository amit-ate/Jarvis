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
        brain.respond("tell me more") { }
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
    fun `failed web search is not spoken and the model answers from its own knowledge`() = runBlocking {
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
        // No bracketed rule in the prompt: the model read it out loud.
        assertFalse(fakeLlm.prompts[0].contains("No internet."))
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

    @Test
    fun `placeholder answer is treated as unsure`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: His sister is ")
            onToken("[Name].")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("Who is Rahul Gandhi's sister?") { out.add(it) }
        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("I'm not sure"), out[0])
    }

    @Test
    fun `a lone garbled word is not sent to the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: No, I'm not sure about that.") }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("This.") { out.add(it) }
        assertEquals(listOf(Brain.DIDNT_CATCH_MSG), out)
        assertEquals(0, fakeLlm.calls)
        brain.respond("Hello") { }
        assertEquals(1, fakeLlm.calls)
    }

    @Test
    fun `answer that restates the question is not an echo`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: The capital of France ")
            onToken("is Paris.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("What is the capital of France?") { out.add(it) }
        assertEquals("The capital of France is Paris.", out.joinToString(" "))
        assertEquals(1, fakeLlm.calls)
    }

    @Test
    fun `chit-chat question is not held to the factual confidence bar`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.3) }) { _, _, _, onToken ->
            onToken("SAY: I think cats ")
            onToken("are wonderful.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("What do you think about cats?") { out.add(it) }
        assertEquals("I think cats are wonderful.", out.joinToString(" "))
    }

    @Test
    fun `bracketed prompt notes are never spoken`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: [Rule: No internet.")
            onToken(" Paris is lovely.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("tell me a joke") { out.add(it) }
        assertTrue(out.none { it.contains("[") || it.contains("Rule") }, out.toString())
        assertTrue(out.joinToString(" ").contains("Paris is lovely."), out.toString())
    }

    @Test
    fun `same answer to the same question again is not a repeat`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: Priyanka is ")
            onToken("Rahul Gandhi's sister.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        brain.respond("Who is Rahul Gandhi's sister?") { }
        val out = mutableListOf<String>()
        brain.respond("Who is Rahul Gandhi's sister?") { out.add(it) }
        assertEquals("Priyanka is Rahul Gandhi's sister.", out.joinToString(" "))
        assertEquals(2, fakeLlm.calls)
    }

    @Test
    fun `unclosed placeholder is treated as unsure`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: The president was ")
            onToken("[Name")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("Who was the president of Zubrovia in 1820?") { out.add(it) }
        assertEquals(1, out.size)
        assertTrue(out[0].startsWith("I'm not sure"), out[0])
    }

    @Test
    fun `very unsure reply to a chat input asks the user to repeat`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.1) }) { _, _, _, onToken ->
            onToken("SAY: Okay,")
            onToken(" do you")
            onToken(" remember?")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("The road Romeo and Juliette") { out.add(it) }
        assertEquals(listOf(Brain.DIDNT_CATCH_MSG), out)
    }

    @Test
    fun `single letters are garbled and how are you is answered directly`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Hmm.") }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val a = mutableListOf<String>()
        brain.respond("S. L. M.") { a.add(it) }
        assertEquals(listOf(Brain.DIDNT_CATCH_MSG), a)
        val b = mutableListOf<String>()
        brain.respond("How are you today?") { b.add(it) }
        assertEquals(listOf(Brain.HOW_ARE_YOU_REPLY), b)
        assertEquals(0, fakeLlm.calls)
    }

    @Test
    fun `nested bracket fragments are removed`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, _, _, onToken ->
            onToken("SAY: Juliet's [Memory[heart]] ")
            onToken("is lovely.")
        }
        val tools = listOf(simpleTimeTool())
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("tell me a joke") { out.add(it) }
        assertTrue(out.none { it.contains("[") || it.contains("]") }, out.toString())
    }

    private fun fakeSearch(result: ToolResult, queries: MutableList<String> = mutableListOf()) = object : Tool {
        override val name = "web_search"
        override val description = "Search"
        override val params: Map<String, List<String>?> = mapOf("query" to null)
        override suspend fun run(args: Map<String, String>): ToolResult { queries.add(args["query"].orEmpty()); return result }
    }

    @Test
    fun `unsure factual answer is looked up online and answered from the result`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { if (it.contains("Lata")) lp(0.004) else lp(0.9) }) { _, prompt, _, onToken ->
            if ("Web search:" in prompt) {
                onToken("SAY: Rabindranath Tagore wrote ")
                onToken("the Indian national anthem.")
            } else {
                onToken("SAY: It was written by ")
                onToken("Lata Mangeshkar.")
            }
        }
        val queries = mutableListOf<String>()
        val tools = listOf(simpleTimeTool(), fakeSearch(ToolResult("Jana Gana Mana...", ok = true, feedToModel = "Rabindranath Tagore, the author of Jana Gana Mana"), queries))
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("Who wrote the Indian national anthem?") { out.add(it) }
        assertEquals(listOf(Brain.LOOKING_UP_MSG, "Rabindranath Tagore wrote the Indian national anthem."), out)
        assertEquals(listOf("Who wrote the Indian national anthem?"), queries)
        assertTrue("Rabindranath Tagore, the author" in fakeLlm.prompts.last())
    }

    @Test
    fun `model hedging on a factual question triggers a lookup`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, prompt, _, onToken ->
            if ("Web search:" in prompt) onToken("SAY: Narendra Modi is the prime minister of India.")
            else onToken("SAY: I don't have the current information on that.")
        }
        val tools = listOf(fakeSearch(ToolResult("PM", ok = true, feedToModel = "Narendra Modi was sworn in as Prime Minister")))
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("Which party does the Indian prime minister belong to?") { out.add(it) }
        assertEquals(listOf(Brain.LOOKING_UP_MSG, "Narendra Modi is the prime minister of India."), out)
    }

    @Test
    fun `failed lookup says it could not find the answer`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.004) }) { _, _, _, onToken ->
            onToken("SAY: The president was ")
            onToken("Rajendra Singh.")
        }
        val tools = listOf(fakeSearch(ToolResult("Sorry, I couldn't find an online answer.", ok = false)))
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("When did the Zubrovian war end?") { out.add(it) }
        assertEquals(listOf(Brain.NOT_FOUND_MSG), out)
    }

    @Test
    fun `searched question is answered from the results, not read out`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.9) }) { _, prompt, _, onToken ->
            if ("Web search:" in prompt) onToken("SAY: Narendra Modi is the prime minister of India.") else onToken("SAY: Wrong path.")
        }
        val queries = mutableListOf<String>()
        val tools = listOf(fakeSearch(ToolResult("India follows a parliamentary system...", ok = true, feedToModel = "Narendra Modi was sworn in as Prime Minister"), queries))
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("Who is the prime minister of India?") { out.add(it) }
        assertEquals(listOf(Brain.LOOKING_UP_MSG, "Narendra Modi is the prime minister of India."), out)
        assertEquals(listOf("Who is the prime minister of India?"), queries)
    }

    @Test
    fun `follow-up question is searched together with the previous one`() {
        val brain = Brain(fast = FastIntents, llm = null, prompts = PromptBuilder(emptyList()), tools = ToolRegistry(emptyList()))
        val turns = listOf(Turn("What is the capital of France?", "SAY: Paris."))
        assertEquals("What is the capital of France? And what about Germany?", brain.searchQuery("And what about Germany?", turns))
        assertEquals("Who wrote Hamlet?", brain.searchQuery("Who wrote Hamlet?", turns))
        assertEquals("And what about Germany?", brain.searchQuery("And what about Germany?", emptyList()))
    }

    @Test
    fun `thanks and goodbyes get a short reply without the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Should not be used.") }
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(emptyList()), tools = ToolRegistry(emptyList()))
        val out = mutableListOf<String>()
        brain.respond("Thank you.") { out.add(it) }
        brain.respond("OK bye") { out.add(it) }
        brain.respond("never mind") { out.add(it) }
        assertEquals(listOf("You're welcome.", "Goodbye!", "Okay."), out)
        assertEquals(0, fakeLlm.calls)
    }

    @Test
    fun `conversions and arithmetic are computed without the model`() = runBlocking {
        val fakeLlm = FakeLlm { _, _, _, onToken -> onToken("SAY: Ten miles is equal to 40.6 kilometers.") }
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(emptyList()), tools = ToolRegistry(emptyList()))
        val out = mutableListOf<String>()
        brain.respond("Convert ten miles to kilometers.") { out.add(it) }
        brain.respond("What is 25 times 4?") { out.add(it) }
        assertEquals(listOf("10 miles is 16.09 kilometers.", "25 times 4 is 100."), out)
        assertEquals(0, fakeLlm.calls)
    }

    @Test
    fun `plain knowledge question is answered by the model before searching`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.95) }) { _, _, _, onToken -> onToken("SAY: The capital of France is Paris.") }
        val queries = mutableListOf<String>()
        val tools = listOf(fakeSearch(ToolResult("Paris...", ok = true), queries))
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(tools), tools = ToolRegistry(tools))
        val out = mutableListOf<String>()
        brain.respond("What is the capital of France?") { out.add(it) }
        assertEquals(listOf("The capital of France is Paris."), out)
        assertTrue(queries.isEmpty())
    }

    @Test
    fun `same-shaped answer with new facts is not a repeat`() = runBlocking {
        val fakeLlm = FakeLlm(logProbFor = { lp(0.95) }) { idx, _, _, onToken ->
            onToken(if (idx == 0) "SAY: The capital of France is Paris." else "SAY: The capital of Germany is Berlin.")
        }
        val brain = Brain(fast = FastIntents, llm = fakeLlm, prompts = PromptBuilder(emptyList()), tools = ToolRegistry(emptyList()))
        val out = mutableListOf<String>()
        brain.respond("What is the capital of France?") { out.add(it) }
        brain.respond("And what about Germany?") { out.add(it) }
        assertEquals(listOf("The capital of France is Paris.", "The capital of Germany is Berlin."), out)
    }
}
