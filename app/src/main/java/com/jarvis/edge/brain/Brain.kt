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
    private val log: (String) -> Unit = {},
    private val expertRouter: ExpertRouter? = null,
    private val shortcutMatcher: ((String) -> ToolCall?)? = null,
    private val selfStateProvider: () -> String? = { null },
    private val memoryProvider: (String) -> String? = { null },
    private val onEpisodeComplete: (userText: String, reply: String, toolCall: ToolCall?, echoed: Boolean) -> Unit = { _, _, _, _ -> },
) {
    private val history = ArrayList<Turn>()
    private var lastTurnMillis: Long = 0L
    private val historyLock = Any()

    fun snapshotHistory(): Pair<List<Turn>, Long> = synchronized(historyLock) { history.toList() to lastTurnMillis }

    fun restoreHistory(turns: List<Turn>, lastTurnMs: Long) {
        if (clockMillis() - lastTurnMs > HISTORY_IDLE_RESET_MS) return
        synchronized(historyLock) {
            history.clear()
            history.addAll(turns.takeLast(PromptBuilder.MAX_HISTORY_TURNS))
            lastTurnMillis = lastTurnMs
        }
    }

    private data class StreamResult(
        val raw: String,
        val spokeAnything: Boolean,
        val abortedForEcho: Boolean,
    )

    suspend fun respond(userText: String, onSentence: suspend (String) -> Unit) {
        val now = clockMillis()
        synchronized(historyLock) {
            if (lastTurnMillis != 0L && now - lastTurnMillis > HISTORY_IDLE_RESET_MS) {
                history.clear()
            }
        }

        // Fast path / learned shortcut is checked first so basic commands work even without a loaded LLM or while HOT.
        val fastCall = fast.match(userText) ?: shortcutMatcher?.invoke(userText)
        fastCall?.let { call ->
            log("fast intent: $call")
            val result = tools.run(call)
            log("tool result: spoken=\"${result.spoken}\" feed=${result.feedToModel?.take(120)}")
            if (result.feedToModel == null || llm == null || !llm.isLoaded || healthGuard?.level() == HealthGuard.Level.HOT) {
                if (result.spoken.isNotBlank()) {
                    onSentence(result.spoken)
                }
                onEpisodeComplete(userText, result.spoken, call, false)
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
                sampling = SamplingConfig(temperature = 0.35f, repeatPenalty = 1.18f),
                fallback = result.spoken,
                onSentence = onSentence,
            )
            recordTurn(userText, "SAY: $answer")
            onEpisodeComplete(userText, answer, call, false)
            return
        }

        val healthLevel = healthGuard?.level() ?: HealthGuard.Level.NORMAL
        if (healthLevel == HealthGuard.Level.HOT) {
            onSentence(OVERHEATING_MSG)
            onEpisodeComplete(userText, OVERHEATING_MSG, null, false)
            return
        }

        val engine = llm
        if (engine == null || !engine.isLoaded) {
            log("no LLM loaded (engine=${engine != null})")
            onSentence(NO_MODEL_MSG)
            onEpisodeComplete(userText, NO_MODEL_MSG, null, false)
            return
        }

        val maxTokens = replyMaxTokens()
        val aboutDevice = PersonalQuery.isAboutDevice(userText)
        val selfState = if (aboutDevice) selfStateProvider() else null
        val memoryContext = memoryProvider(userText)
        val askedAttr = if (aboutDevice) null else PersonalQuery.askedAttribute(userText)
        if (askedAttr != null && memoryContext.isNullOrBlank()) {
            val reply = "I don't know your $askedAttr yet. Tell me and I'll remember it."
            onSentence(reply)
            recordTurn(userText, "SAY: $reply")
            onEpisodeComplete(userText, reply, null, false)
            return
        }
        val sampling = expertRouter?.selectAndActivate(userText, engine) ?: SamplingConfig()
        val promptText = prompts.prompt(
            history = activeHistory(),
            user = userText,
            selfStateLine = selfState,
            memoryContext = memoryContext,
        )
        val t0 = clockMillis()
        val firstRun = streamSayOrCollectTool(
            engine = engine,
            prompt = promptText,
            grammar = prompts.grammar(),
            maxTokens = maxTokens,
            sampling = sampling,
            echoGuardUserText = userText,
            onSentence = onSentence,
        )

        val parsed = parseModelOutput(firstRun.raw)
        log("llm raw (${clockMillis() - t0} ms, maxTokens=$maxTokens): \"${firstRun.raw.take(300)}\" -> $parsed")
        when (parsed) {
            is ModelOutput.Say -> {
                val replyText = parsed.text
                if (!firstRun.abortedForEcho && replyText.isNotBlank() && !EchoGuard.isEcho(userText, replyText)) {
                    recordTurn(userText, "SAY: $replyText")
                    onEpisodeComplete(userText, replyText, null, false)
                    return
                }

                // Echo detected (either buffered at first sentence or full reply) -> retry once with anti-echo sampling.
                if (firstRun.abortedForEcho || replyText.isBlank() || EchoGuard.isEcho(userText, replyText)) {
                    log("EchoGuard triggered on first pass; retrying with anti-echo sampling")
                    val retryPrompt = prompts.prompt(
                        history = activeHistory(),
                        user = userText,
                        selfStateLine = selfState,
                        memoryContext = memoryContext,
                        extraNudge = ANTI_ECHO_NUDGE,
                    )
                    val retryRun = streamSayOrCollectTool(
                        engine = engine,
                        prompt = retryPrompt,
                        grammar = prompts.sayOnlyGrammar(),
                        maxTokens = maxTokens,
                        sampling = RETRY_SAMPLING,
                        echoGuardUserText = userText,
                        onSentence = if (firstRun.spokeAnything) ({}) else onSentence,
                    )
                    val retryParsed = parseModelOutput(retryRun.raw)
                    val retryText = (retryParsed as? ModelOutput.Say)?.text.orEmpty()
                    if (!retryRun.abortedForEcho && retryText.isNotBlank() && !EchoGuard.isEcho(userText, retryText)) {
                        recordTurn(userText, "SAY: $retryText")
                        onEpisodeComplete(userText, retryText, null, false)
                        return
                    }

                    // Retry still echoed -> try web_search fallback for factual questions, else safe fallback.
                    if (EchoGuard.looksLikeFactualQuestion(userText)) {
                        val searchCall = ToolCall("web_search", mapOf("query" to userText))
                        val searchResult = tools.run(searchCall)
                        if (searchResult.ok && searchResult.spoken.isNotBlank()) {
                            if (!firstRun.spokeAnything && !retryRun.spokeAnything) {
                                onSentence(searchResult.spoken)
                            }
                            onEpisodeComplete(userText, searchResult.spoken, searchCall, true)
                            return
                        }
                    }

                    if (!firstRun.spokeAnything && !retryRun.spokeAnything) {
                        onSentence(ECHO_FALLBACK_MSG)
                    }
                    onEpisodeComplete(userText, ECHO_FALLBACK_MSG, null, true)
                }
            }
            is ModelOutput.Call -> {
                val result = tools.run(parsed.call)
                log("tool result: spoken=\"${result.spoken}\" feed=${result.feedToModel?.take(120)}")
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
                        sampling = SamplingConfig(temperature = 0.35f, repeatPenalty = 1.18f),
                        fallback = result.spoken,
                        onSentence = onSentence,
                    )
                    recordTurn(userText, "SAY: $reply")
                    onEpisodeComplete(userText, reply, parsed.call, false)
                } else {
                    if (result.spoken.isNotBlank()) {
                        onSentence(result.spoken)
                        recordTurn(userText, "SAY: ${result.spoken}")
                    }
                    onEpisodeComplete(userText, result.spoken, parsed.call, false)
                }
            }
        }
    }

    private suspend fun streamSayResponse(
        prompt: String,
        grammar: String,
        maxTokens: Int,
        sampling: SamplingConfig,
        fallback: String,
        onSentence: suspend (String) -> Unit,
    ): String {
        val engine = llm ?: return fallback
        val collected = streamSayOrCollectTool(
            engine = engine,
            prompt = prompt,
            grammar = grammar,
            maxTokens = maxTokens,
            sampling = sampling,
            echoGuardUserText = null,
            onSentence = onSentence,
        )
        return when (val out = parseModelOutput(collected.raw)) {
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
        sampling: SamplingConfig,
        echoGuardUserText: String?,
        onSentence: suspend (String) -> Unit,
    ): StreamResult = coroutineScope {
        val raw = StringBuilder()
        val splitter = SentenceSplitter(splitClauses = true)
        val sentences = Channel<String>(Channel.UNLIMITED)

        val cancelledByConsumer = AtomicBoolean(false)

        val producer = launch(llmDispatcher) {
            var mode = StreamMode.UNKNOWN
            var prefixSkipped = false
            try {
                engine.generate(prompt, grammar, maxTokens, sampling) { piece ->
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

        var firstChecked = false
        var spokeAnything = false
        var abortedForEcho = false
        try {
            for (sentence in sentences) {
                if (!firstChecked) {
                    firstChecked = true
                    if (echoGuardUserText != null && EchoGuard.isEcho(echoGuardUserText, sentence)) {
                        abortedForEcho = true
                        cancelledByConsumer.set(true)
                        break
                    }
                }
                spokeAnything = true
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
        StreamResult(
            raw = raw.toString(),
            spokeAnything = spokeAnything,
            abortedForEcho = abortedForEcho,
        )
    }

    private fun replyMaxTokens(): Int =
        if (healthGuard?.level() == HealthGuard.Level.WARM) 80 else 160

    private fun activeHistory(): List<Turn> = synchronized(historyLock) { history.toList() }

    private fun recordTurn(user: String, assistant: String) {
        val now = clockMillis()
        synchronized(historyLock) {
            history.add(Turn(user = user, assistant = assistant))
            while (history.size > PromptBuilder.MAX_HISTORY_TURNS) {
                history.removeAt(0)
            }
            lastTurnMillis = now
        }
    }

    fun clearHistory() {
        synchronized(historyLock) {
            history.clear()
            lastTurnMillis = 0L
        }
    }

    private enum class StreamMode { UNKNOWN, SAY, TOOL }

    companion object {
        const val HISTORY_IDLE_RESET_MS = 900_000L
        const val NO_MODEL_MSG = "My language model isn't loaded, so I can only handle basic commands."
        const val OVERHEATING_MSG = "I'm overheating, so I'll keep to basic commands."
        const val ECHO_FALLBACK_MSG = "I'm not sure how to answer that yet."
        private const val ANTI_ECHO_NUDGE = "Do NOT repeat the user's words. Answer directly."
        private val RETRY_SAMPLING = SamplingConfig(
            temperature = 0.72f,
            repeatPenalty = 1.32f,
            freqPenalty = 0.25f,
            presencePenalty = 0.20f,
            penalizeUserTokens = 24,
        )
    }
}
