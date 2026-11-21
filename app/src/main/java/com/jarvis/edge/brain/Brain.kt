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
    private val personalFacts: PersonalFactStore? = null,
    private val onEpisodeComplete: (userText: String, reply: String, toolCall: ToolCall?, echoed: Boolean) -> Unit = { _, _, _, _ -> },
    /** The user corrected a misheard name ("It is Rahul Gandhi"): learn it so the recogniser gets it right next time. */
    private val onEntityCorrected: (heard: String, meant: String) -> Unit = { _, _ -> },
) {
    /** Last thing the user said (whether or not it went into history), so a follow-up correction can refer to it. */
    @Volatile
    private var lastUserUtterance: String? = null

    @Volatile
    private var lastTaughtFact: TaughtFact? = null
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
        /** The first sentence was a low-confidence answer to a factual question and was not spoken. */
        val abortedUnsure: Boolean = false,
        /** A later sentence was dropped (repeat or low confidence); [spokenText] is what was actually said. */
        val truncated: Boolean = false,
        val spokenText: String = "",
        /** Confidence over every sentence the model produced, spoken or not. */
        val confidence: Confidence = Confidence.NONE,
    )

    private enum class Gate { SPEAK, REJECT, UNSURE, TRUNCATE }

    /**
     * Answers [userText]. [heardConfidence] (0..1) is how sure speech recognition was of the words; it decides
     * whether an unsure answer asks the user to repeat or says it does not know.
     */
    suspend fun respond(userText: String, heardConfidence: Float = 1f, onSentence: suspend (String) -> Unit) =
        respondTurn(userText, heardConfidence, allowFix = true, onSentence = onSentence)

    private suspend fun respondTurn(
        userText: String,
        heardConfidence: Float,
        allowFix: Boolean,
        onSentence: suspend (String) -> Unit,
    ) {
        val now = clockMillis()
        synchronized(historyLock) {
            if (lastTurnMillis != 0L && now - lastTurnMillis > HISTORY_IDLE_RESET_MS) {
                history.clear()
            }
        }

        // Following the conversation: "It is Rahul Gandhi" right after a question with a misheard name re-answers that
        // question with the name fixed, and teaches the recogniser the correction.
        val previous = lastUserUtterance
        if (allowFix) {
            val fix = ConversationFollow.entityFix(userText, previous)
            if (fix != null) {
                log("entity fix: \"${fix.heard}\" -> \"${fix.meant}\"; re-answering \"${fix.correctedText}\"")
                runCatching { onEntityCorrected(fix.heard, fix.meant) }
                lastTaughtFact?.let { tf ->
                    if (tf.statement().contains(fix.heard, ignoreCase = true)) {
                        runCatching { personalFacts?.forgetStatement(tf.statement()) }
                    }
                }
                lastTaughtFact = null
                dropLastTurnFor(previous)
                onSentence("Got it, ${fix.meant}.")
                respondTurn(fix.correctedText, 1f, allowFix = false, onSentence = onSentence)
                return
            }
        }
        lastUserUtterance = userText

        // Fast path / learned shortcut is checked first so basic commands work even without a loaded LLM or while HOT.
        val engineReady = llm?.isLoaded == true && healthGuard?.level() != HealthGuard.Level.HOT
        var offlineNudge: String? = null
        val fastCall = fast.match(userText) ?: shortcutMatcher?.invoke(userText)
        if (fastCall != null) {
            log("fast intent: $fastCall")
            val result = tools.run(fastCall)
            log("tool result: spoken=\"${result.spoken}\" feed=${result.feedToModel?.take(120)}")
            if (fastCall.name == "web_search" && !result.ok && engineReady) {
                // Offline search: do not speak the failure; let the model answer from its own knowledge.
                offlineNudge = OFFLINE_NUDGE
            } else {
                // A tool that already produced a spoken sentence is spoken as it is. The model only
                // summarizes a tool that gave data but no sentence, and only when it is usable.
                if (result.spoken.isNotBlank() || result.feedToModel == null || !engineReady) {
                    if (result.spoken.isNotBlank()) {
                        onSentence(result.spoken)
                    }
                    onEpisodeComplete(userText, result.spoken, fastCall, false)
                    return
                }
                val obsPrompt = prompts.prompt(
                    activeHistory(),
                    "User asked: $userText\nTool ${fastCall.name} observed:\n${result.feedToModel}\nSummarize concisely for the user.",
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
                onEpisodeComplete(userText, answer, fastCall, false)
                return
            }
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
        val stated = if (aboutDevice || personalFacts == null) null else PersonalQuery.statedAttribute(userText)
        if (stated != null) {
            val (attr, value) = stated
            // Not recorded in history: the model copied these replies into unrelated answers.
            val reply = runCatching { personalFacts!!.remember(attr, value) }
                .fold({ "Got it, your $attr is $value. I'll remember that." }, { "Sorry, I couldn't save that." })
            onSentence(reply)
            onEpisodeComplete(userText, reply, null, false)
            return
        }
        val taught = if (personalFacts == null) null else ConversationFollow.taughtFact(userText)
        if (taught != null) {
            val statement = taught.statement()
            val reply = runCatching { personalFacts!!.rememberStatement(statement) }
                .fold({ "Thanks, I'll remember that $statement." }, { "Sorry, I couldn't save that." })
            lastTaughtFact = taught
            log("taught fact: $statement")
            onSentence(reply)
            recordTurn(userText, "SAY: $reply")
            onEpisodeComplete(userText, reply, null, false)
            return
        }
        val askedAttr = if (aboutDevice) null else PersonalQuery.askedAttribute(userText)
        if (askedAttr != null) {
            val known = personalFacts?.let { runCatching { it.recall(askedAttr) }.getOrNull() }
            if (known != null || memoryContext.isNullOrBlank()) {
                val reply = if (known != null) "Your $askedAttr is $known." else "I don't know your $askedAttr yet. Tell me and I'll remember it."
                onSentence(reply)
                onEpisodeComplete(userText, reply, null, false)
                return
            }
        }
        val sampling = expertRouter?.selectAndActivate(userText, engine) ?: SamplingConfig()
        val turns = activeHistory()
        // Every sentence is checked before it is spoken: an echo of the user or a repeat of an earlier reply is
        // rejected; for a factual question, a low-confidence first sentence (a guess) is replaced by an honest
        // "not sure", and a low-confidence later sentence ends the reply.
        val factual = EchoGuard.looksLikeFactualQuestion(userText)
        val gate: (Int, String, Confidence) -> Gate = { i, s, c ->
            val repeat = repeatsHistory(s, turns)
            when {
                i == 0 && (repeat || EchoGuard.isEcho(userText, s)) -> Gate.REJECT
                i == 0 && factual && c.isUncertain() -> Gate.UNSURE
                i > 0 && (repeat || (factual && c.isUncertain()) || c.score < VERY_UNSURE_SCORE) -> Gate.TRUNCATE
                else -> Gate.SPEAK
            }
        }
        val promptText = prompts.prompt(
            history = turns,
            user = userText,
            selfStateLine = selfState,
            memoryContext = memoryContext,
            extraNudge = offlineNudge,
        )
        val t0 = clockMillis()
        val firstRun = streamSayOrCollectTool(
            engine = engine,
            prompt = promptText,
            grammar = prompts.grammar(),
            maxTokens = maxTokens,
            sampling = sampling,
            gate = gate,
            onSentence = onSentence,
        )

        val parsed = parseModelOutput(firstRun.raw)
        log("llm raw (${clockMillis() - t0} ms, maxTokens=$maxTokens): \"${firstRun.raw.take(300)}\" -> $parsed")
        log(
            "confidence: heard=${"%.2f".format(java.util.Locale.US, heardConfidence)} answer=${firstRun.confidence.describe()} " +
                "factual=$factual -> " + when {
                    firstRun.abortedUnsure -> "withheld (unsure)"
                    firstRun.abortedForEcho -> "rejected (echo/repeat)"
                    firstRun.truncated -> "truncated"
                    else -> "spoken"
                },
        )
        if (firstRun.abortedUnsure) {
            val msg = unsureReply(heardConfidence, offline = offlineNudge != null)
            onSentence(msg)
            recordTurn(userText, "SAY: $msg")
            onEpisodeComplete(userText, msg, null, false)
            return
        }
        when (parsed) {
            is ModelOutput.Say -> {
                val replyText = if (firstRun.truncated) firstRun.spokenText else parsed.text
                if (firstRun.spokeAnything) {
                    // Already said out loud: a retry would only talk over it. Keep what was spoken.
                    val echoed = EchoGuard.isEcho(userText, replyText) || repeatsHistory(replyText, turns)
                    if (!echoed) recordTurn(userText, "SAY: $replyText")
                    onEpisodeComplete(userText, replyText, null, echoed)
                    return
                }
                val repeats = repeatsHistory(replyText, turns)
                val echoed = firstRun.abortedForEcho || replyText.isBlank() || EchoGuard.isEcho(userText, replyText) || repeats
                if (!echoed) {
                    recordTurn(userText, "SAY: $replyText")
                    onEpisodeComplete(userText, replyText, null, false)
                    return
                }

                // Echo or repeat detected (either buffered at first sentence or full reply) -> retry once with anti-echo sampling.
                run {
                    log("EchoGuard or repeat triggered on first pass; retrying with anti-echo sampling")
                    val retryPrompt = prompts.prompt(
                        history = turns,
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
                        gate = gate,
                        onSentence = onSentence,
                    )
                    if (retryRun.abortedUnsure) {
                        val msg = unsureReply(heardConfidence, offline = offlineNudge != null)
                        onSentence(msg)
                        recordTurn(userText, "SAY: $msg")
                        onEpisodeComplete(userText, msg, null, false)
                        return
                    }
                    val retryParsed = parseModelOutput(retryRun.raw)
                    val retryText = if (retryRun.truncated) retryRun.spokenText else (retryParsed as? ModelOutput.Say)?.text.orEmpty()
                    if (!retryRun.abortedForEcho && retryText.isNotBlank() &&
                        !EchoGuard.isEcho(userText, retryText) && !repeatsHistory(retryText, turns)
                    ) {
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
            gate = null,
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
        gate: ((index: Int, sentence: String, confidence: Confidence) -> Gate)?,
        onSentence: suspend (String) -> Unit,
    ): StreamResult = coroutineScope {
        val raw = StringBuilder()
        val splitter = SentenceSplitter(splitClauses = true)
        val sentences = Channel<Pair<String, Confidence>>(Channel.UNLIMITED)
        // Producer-thread only: token log-probs since the last emitted sentence.
        val meter = ConfidenceMeter()
        // The current token counts toward the sentence it completes; otherwise it starts the next sentence.
        var currentLp = Float.NaN
        var currentCounted = true
        fun emit(s: String) {
            if (!currentCounted && raw.toString().trimEnd().endsWith(s.trimEnd())) {
                meter.add(currentLp)
                currentCounted = true
            }
            sentences.trySend(s to meter.takeAndReset())
        }
        fun settleCurrent() {
            if (!currentCounted) meter.add(currentLp)
            currentCounted = true
        }

        val cancelledByConsumer = AtomicBoolean(false)

        val producer = launch(llmDispatcher) {
            var mode = StreamMode.UNKNOWN
            var prefixSkipped = false
            try {
                engine.generate(prompt, grammar, maxTokens, sampling) { piece ->
                    if (cancelledByConsumer.get()) return@generate false
                    settleCurrent()
                    raw.append(piece)
                    currentLp = engine.lastTokenLogProb
                    currentCounted = false

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
                                        emit(s)
                                    }
                                }
                                return@generate !cancelledByConsumer.get()
                            }
                            trimmed.length >= 4 && !"say:".startsWith(trimmed.lowercase()) -> {
                                // Fallback if model omitted SAY: prefix.
                                mode = StreamMode.SAY
                                prefixSkipped = true
                                for (s in splitter.push(raw.toString())) {
                                    emit(s)
                                }
                                return@generate !cancelledByConsumer.get()
                            }
                        }
                    } else if (mode == StreamMode.SAY && prefixSkipped) {
                        for (s in splitter.push(piece)) {
                            emit(s)
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
                                emit(s)
                            }
                        }
                        mode = StreamMode.SAY
                    }
                }
                if (!cancelledByConsumer.get() && mode == StreamMode.SAY) {
                    settleCurrent()
                    splitter.flush()?.let { rem -> emit(rem) }
                }
            } finally {
                sentences.close()
            }
        }

        var index = 0
        var spokeAnything = false
        var abortedForEcho = false
        var abortedUnsure = false
        var truncated = false
        val spoken = ArrayList<String>()
        val seen = ArrayList<Confidence>()
        try {
            for ((sentence, conf) in sentences) {
                seen.add(conf)
                val decision = gate?.invoke(index, sentence, conf) ?: Gate.SPEAK
                index++
                if (decision != Gate.SPEAK) {
                    when (decision) {
                        Gate.REJECT -> abortedForEcho = true
                        Gate.UNSURE -> abortedUnsure = true
                        else -> truncated = true
                    }
                    log("sentence ${index - 1} ${decision.name.lowercase()}: \"${sentence.take(80)}\" (${conf.describe()})")
                    cancelledByConsumer.set(true)
                    break
                }
                spokeAnything = true
                spoken.add(sentence)
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
            abortedUnsure = abortedUnsure,
            truncated = truncated,
            spokenText = spoken.joinToString(" "),
            confidence = combine(seen),
        )
    }

    /** True when [reply] is a near-duplicate of any earlier assistant turn in [turns]. */
    private fun repeatsHistory(reply: String, turns: List<Turn>): Boolean {
        val norm = normalizeForRepeat(reply)
        if (norm.isEmpty()) return false
        return turns.any { turn ->
            val prev = stripSayPrefix(turn.assistant)
            normalizeForRepeat(prev) == norm || EchoGuard.isEcho(prev, reply)
        }
    }

    private fun normalizeForRepeat(text: String): String =
        stripSayPrefix(text).lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun stripSayPrefix(text: String): String {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("SAY:", ignoreCase = true)) trimmed.substring(4).trimStart() else trimmed
    }

    private fun unsureReply(heardConfidence: Float, offline: Boolean): String = when {
        heardConfidence < HEARD_UNSURE -> "I'm not sure I heard that right. Could you say it again?"
        offline -> "I'm not sure about that, and I can't look it up without internet."
        else -> "I'm not sure about that."
    }

    /** Removes the newest history turn if it belongs to [userText] (it is being replaced by a corrected version). */
    private fun dropLastTurnFor(userText: String?) {
        if (userText == null) return
        synchronized(historyLock) {
            if (history.isNotEmpty() && history.last().user == userText) history.removeAt(history.size - 1)
        }
    }

    private fun replyMaxTokens(): Int =
        if (healthGuard?.level() == HealthGuard.Level.WARM) 80 else 160

    private fun activeHistory(): List<Turn> = synchronized(historyLock) { history.toList() }

    private fun recordTurn(user: String, assistant: String) {
        val now = clockMillis()
        synchronized(historyLock) {
            history.add(Turn(user = user, assistant = assistant))
            // Drop a whole window at a time (see PromptBuilder.selectHistory) so the prompt prefix, and with it the
            // KV cache, stays the same for most turns instead of shifting by one turn every time.
            if (history.size > 2 * PromptBuilder.MAX_HISTORY_TURNS) {
                repeat(PromptBuilder.MAX_HISTORY_TURNS) { history.removeAt(0) }
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
        /** Below this, speech recognition probably got words wrong: ask the user to repeat rather than say "not sure". */
        private const val HEARD_UNSURE = 0.5f
        /** A later sentence this unsure is cut even in chat (the model is rambling). */
        private const val VERY_UNSURE_SCORE = 0.35f
        private const val ANTI_ECHO_NUDGE = "Do NOT repeat the user's words. Answer directly."
        private const val OFFLINE_NUDGE = "No internet. Answer from your own knowledge in one sentence; if unsure, say you are not sure."
        private val RETRY_SAMPLING = SamplingConfig(
            temperature = 0.72f,
            repeatPenalty = 1.32f,
            freqPenalty = 0.25f,
            presencePenalty = 0.20f,
            penalizeUserTokens = 24,
        )
    }
}
