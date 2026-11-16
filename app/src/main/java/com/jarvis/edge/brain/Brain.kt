package com.jarvis.edge.brain

import com.jarvis.edge.core.SentenceSplitter
import com.jarvis.edge.service.HealthGuard
import com.jarvis.edge.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class Brain(
    private val fast: FastIntents = FastIntents,
    private val llm: LlmEngine?,
    private val prompts: PromptBuilder,
    private val tools: ToolRegistry,
    private val healthGuard: HealthGuard? = null,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val llmDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val history = ArrayList<Turn>()
    private var lastTurnMillis: Long = 0L

    suspend fun respond(userText: String, onSentence: suspend (String) -> Unit) {
        val now = clockMillis()
        if (lastTurnMillis != 0L && now - lastTurnMillis > HISTORY_IDLE_RESET_MS) {
            history.clear()
        }

        // Fast path is checked first so basic commands work even without a loaded LLM or while HOT.
        fast.match(userText)?.let { call ->
            val result = tools.run(call)
            if (result.feedToModel == null || llm == null || !llm.isLoaded || healthGuard?.level() == HealthGuard.Level.HOT) {
                if (result.spoken.isNotBlank()) {
                    onSentence(result.spoken)
                }
                return
            }
            val obsPrompt = prompts.prompt(
                activeHistory(),
                "User asked: $userText\nTool ${call.name} observed:\n${result.feedToModel}\nSummarize concisely for the user.",
            )
            val answer = streamSayResponse(
                prompt = obsPrompt,
                grammar = prompts.sayOnlyGrammar(),
                maxTokens = replyMaxTokens(),
                fallback = result.spoken,
                onSentence = onSentence,
            )
            recordTurn(userText, "SAY: $answer")
            return
        }

        val healthLevel = healthGuard?.level() ?: HealthGuard.Level.NORMAL
        if (healthLevel == HealthGuard.Level.HOT) {
            onSentence(OVERHEATING_MSG)
            return
        }

        val engine = llm
        if (engine == null || !engine.isLoaded) {
            onSentence(NO_MODEL_MSG)
            return
        }

        val maxTokens = replyMaxTokens()
        val promptText = prompts.prompt(activeHistory(), userText)
        val rawOutput = streamSayOrCollectTool(
            engine = engine,
            prompt = promptText,
            grammar = prompts.grammar(),
            maxTokens = maxTokens,
            onSentence = onSentence,
        )

        val parsed = parseModelOutput(rawOutput)
        when (parsed) {
            is ModelOutput.Say -> {
                if (parsed.text.isNotBlank()) {
                    recordTurn(userText, "SAY: ${parsed.text}")
                }
            }
            is ModelOutput.Call -> {
                val result = tools.run(parsed.call)
                val feed = result.feedToModel
                if (feed != null) {
                    val followUpPrompt = prompts.prompt(
                        activeHistory(),
                        "User asked: $userText\nTool ${parsed.call.name} returned:\n$feed\nAnswer the user clearly.",
                    )
                    val reply = streamSayResponse(
                        prompt = followUpPrompt,
                        grammar = prompts.sayOnlyGrammar(),
                        maxTokens = maxTokens,
                        fallback = result.spoken,
                        onSentence = onSentence,
                    )
                    recordTurn(userText, "SAY: $reply")
                } else {
                    if (result.spoken.isNotBlank()) {
                        onSentence(result.spoken)
                        recordTurn(userText, "SAY: ${result.spoken}")
                    }
                }
            }
        }
    }

    private suspend fun streamSayResponse(
        prompt: String,
        grammar: String,
        maxTokens: Int,
        fallback: String,
        onSentence: suspend (String) -> Unit,
    ): String {
        val engine = llm ?: return fallback
        val collected = streamSayOrCollectTool(
            engine = engine,
            prompt = prompt,
            grammar = grammar,
            maxTokens = maxTokens,
            onSentence = onSentence,
        )
        return when (val out = parseModelOutput(collected)) {
            is ModelOutput.Say -> {
                if (out.text.isNotBlank()) {
                    out.text
                } else {
                    if (fallback.isNotBlank()) onSentence(fallback)
                    fallback
                }
            }
            is ModelOutput.Call -> {
                if (fallback.isNotBlank()) onSentence(fallback)
                fallback
            }
        }
    }

    private suspend fun streamSayOrCollectTool(
        engine: LlmEngine,
        prompt: String,
        grammar: String?,
        maxTokens: Int,
        onSentence: suspend (String) -> Unit,
    ): String = coroutineScope {
        val raw = StringBuilder()
        val splitter = SentenceSplitter()
        val sentences = Channel<String>(Channel.UNLIMITED)

        val cancelledByConsumer = AtomicBoolean(false)

        val producer = launch(llmDispatcher) {
            var mode = StreamMode.UNKNOWN
            var prefixSkipped = false
            try {
                engine.generate(prompt, grammar, maxTokens) { piece ->
                    if (cancelledByConsumer.get()) return@generate false
                    raw.append(piece)

                    if (mode == StreamMode.TOOL) {
                        return@generate !cancelledByConsumer.get()
                    }

                    if (mode == StreamMode.UNKNOWN) {
                        val trimmed = raw.toString().trimStart()
                        when {
                            trimmed.startsWith("{") -> {
                                mode = StreamMode.TOOL
                                return@generate !cancelledByConsumer.get()
                            }
                            trimmed.startsWith("SAY:", ignoreCase = true) -> {
                                mode = StreamMode.SAY
                                prefixSkipped = true
                                val colonIdx = raw.indexOf(":")
                                val afterPrefix = raw.substring(colonIdx + 1).trimStart()
                                if (afterPrefix.isNotEmpty()) {
                                    for (s in splitter.push(afterPrefix)) {
                                        sentences.trySend(s)
                                    }
                                }
                                return@generate !cancelledByConsumer.get()
                            }
                            trimmed.length >= 4 && !"say:".startsWith(trimmed.lowercase()) -> {
                                // Fallback if model omitted SAY: prefix.
                                mode = StreamMode.SAY
                                prefixSkipped = true
                                for (s in splitter.push(raw.toString())) {
                                    sentences.trySend(s)
                                }
                                return@generate !cancelledByConsumer.get()
                            }
                        }
                    } else if (mode == StreamMode.SAY && prefixSkipped) {
                        for (s in splitter.push(piece)) {
                            sentences.trySend(s)
                        }
                    }
                    !cancelledByConsumer.get()
                }

                if (!cancelledByConsumer.get() && mode == StreamMode.UNKNOWN) {
                    val trimmed = raw.toString().trimStart()
                    if (!trimmed.startsWith("{")) {
                        val text = if (trimmed.startsWith("SAY:", ignoreCase = true)) {
                            trimmed.substring(4).trimStart()
                        } else {
                            trimmed
                        }
                        if (text.isNotEmpty()) {
                            for (s in splitter.push(text)) {
                                sentences.trySend(s)
                            }
                        }
                        mode = StreamMode.SAY
                    }
                }
                if (!cancelledByConsumer.get() && mode == StreamMode.SAY) {
                    splitter.flush()?.let { rem -> sentences.trySend(rem) }
                }
            } finally {
                sentences.close()
            }
        }

        try {
            for (sentence in sentences) {
                onSentence(sentence)
            }
        } catch (ce: CancellationException) {
            cancelledByConsumer.set(true)
            producer.cancel(ce)
            throw ce
        } catch (t: Throwable) {
            cancelledByConsumer.set(true)
            producer.join()
            throw t
        }
        producer.join()
        raw.toString()
    }

    private fun replyMaxTokens(): Int =
        if (healthGuard?.level() == HealthGuard.Level.WARM) 80 else 160

    private fun activeHistory(): List<Turn> = history.toList()

    private fun recordTurn(user: String, assistant: String) {
        history.add(Turn(user = user, assistant = assistant))
        while (history.size > PromptBuilder.MAX_HISTORY_TURNS) {
            history.removeAt(0)
        }
        lastTurnMillis = clockMillis()
    }

    fun clearHistory() {
        history.clear()
        lastTurnMillis = 0L
    }

    private enum class StreamMode { UNKNOWN, SAY, TOOL }

    companion object {
        const val HISTORY_IDLE_RESET_MS = 120_000L
        const val NO_MODEL_MSG = "My language model isn't loaded, so I can only handle basic commands."
        const val OVERHEATING_MSG = "I'm overheating, so I'll keep to basic commands."
    }
}
