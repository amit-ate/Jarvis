package com.jarvis.edge.brain

import kotlin.math.ln

import com.jarvis.edge.core.SentenceSplitter
import com.jarvis.edge.service.HealthGuard
import com.jarvis.edge.tools.ToolRegistry
import com.jarvis.edge.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
    /** Start the web search alongside the model's first answer for factual questions (on in the app, off in tests). */
    private val prefetchLookups: Boolean = false,
    /** A web lookup started: the app shows a searching animation and plays a cue instead of saying LOOKING_UP_MSG. */
    private val onLookupStarted: (() -> Unit)? = null,
) {
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toolNames: Set<String> by lazy { tools.all().map { it.name }.toSet() }
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

    /** FINISH: stop after the current sentence (a low-confidence clause never leaves a half sentence hanging). */
    private enum class Gate { SPEAK, REJECT, UNSURE, TRUNCATE, FINISH }

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

        // Arithmetic and unit conversions are computed, not generated: the 1B model said 10 miles was 40.6 km.
        QuickMath.answer(userText)?.let { math ->
            log("quick math: $math")
            onSentence(math)
            recordTurn(userText, "SAY: $math")
            onEpisodeComplete(userText, math, null, false)
            return
        }

        // Fast path / learned shortcut is checked first so basic commands work even without a loaded LLM or while HOT.
        val engineReady = llm?.isLoaded == true && healthGuard?.level() != HealthGuard.Level.HOT
        var offlineNudge: String? = null
        val fastCall = (fast.match(userText) ?: shortcutMatcher?.invoke(userText))?.takeUnless { call ->
            // A plain knowledge question ("What is the capital of France?") is answered by the model first — about 5 s
            // instead of ~15 s for search + reading the results — and looked up only if the answer is unsure.
            // Explicit searches and questions whose answer changes over time still go to the web first.
            call.name == "web_search" && llm?.isLoaded == true && healthGuard?.level() != HealthGuard.Level.HOT &&
                EchoGuard.looksLikeFactualQuestion(userText) && !TIME_SENSITIVE.containsMatchIn(userText) &&
                !EXPLICIT_SEARCH.containsMatchIn(userText.trim())
        }
        if (fastCall != null) {
            log("fast intent: $fastCall")
            // A spoken question is searched as asked ("Who wrote ...?" finds better snippets than the bare topic) and,
            // with the model loaded, answered from the results instead of reading out the first snippet.
            val groundSearch = fastCall.name == "web_search" && engineReady && tools.has("web_search")
            val searchCall = if (groundSearch && EchoGuard.looksLikeFactualQuestion(userText)) {
                ToolCall("web_search", mapOf("query" to searchQuery(userText, activeHistory())))
            } else {
                fastCall
            }
            if (groundSearch) onLookupStarted?.invoke()
            val result = tools.run(searchCall)
            if (groundSearch && result.ok && result.spoken.isNotBlank()) {
                if (onLookupStarted == null) onSentence(LOOKING_UP_MSG) // covers the prefill of the search results
                val answer = groundedAnswer(userText, result, activeHistory(), onSentence)
                recordTurn(userText, "SAY: $answer", sent = lastGroundedSent)
                onEpisodeComplete(userText, answer, searchCall, false)
                return
            }
            log("tool result: spoken=\"${result.spoken}\" feed=${result.feedToModel?.take(120)}")
            if (fastCall.name == "web_search" && !result.ok && engineReady) {
                // Offline search: do not speak the failure; let the model answer from its own knowledge. No prompt note:
                // the model read "[Rule: No internet...]" out loud; the confidence check covers unsure answers.
                offlineNudge = ""
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
        val closing = closingReply(userText)
        if (closing != null) {
            // Canned: the model's "You're welcome!" was rejected as a repeat of an earlier one.
            onSentence(closing)
            onEpisodeComplete(userText, closing, null, false)
            return
        }
        if (HOW_ARE_YOU.containsMatchIn(userText.trim())) {
            onSentence(HOW_ARE_YOU_REPLY)
            recordTurn(userText, "SAY: $HOW_ARE_YOU_REPLY")
            onEpisodeComplete(userText, HOW_ARE_YOU_REPLY, null, false)
            return
        }
        if (looksGarbled(userText)) {
            log("garbled input, not answering: \"$userText\"")
            onSentence(DIDNT_CATCH_MSG)
            onEpisodeComplete(userText, DIDNT_CATCH_MSG, null, false)
            return
        }
        val sampling = expertRouter?.selectAndActivate(userText, engine) ?: SamplingConfig()
        val turns = activeHistory()
        // Every sentence is checked before it is spoken: an echo of the user or a repeat of an earlier reply is
        // rejected; for a factual question, a low-confidence first sentence (a guess) is replaced by an honest
        // "not sure", and a low-confidence later sentence ends the reply.
        // A follow-up like "and the second tallest" leans on a factual question before it, so it is one too.
        val followsFact = ELLIPTICAL_FOLLOW_UP.containsMatchIn(userText.trim()) &&
            turns.lastOrNull()?.user?.let(EchoGuard::looksLikeFactualQuestion) == true
        val factual = (EchoGuard.looksLikeFactualQuestion(userText) || followsFact) && !CHITCHAT.containsMatchIn(userText.trim()) &&
            !ABOUT_JARVIS.containsMatchIn(userText.trim())
        // With a web lookup to fall back on, a guess is cheaper to replace, so a weaker token is enough to check.
        val canLookUp = offlineNudge == null && heardConfidence >= HEARD_UNSURE && tools.has("web_search")
        val gate: (Int, String, Confidence) -> Gate = { i, s, c ->
            val repeat = repeatsHistory(s, turns, userText)
            when {
                i == 0 && (repeat || EchoGuard.isEcho(userText, s)) -> Gate.REJECT
                // "I can update my knowledge using the get_weather tool", "I'm running": claims nothing backs up.
                ClaimGuard.problem(s, toolNames, toolRan = false) != null -> if (i == 0) Gate.REJECT else Gate.TRUNCATE
                i == 0 && factual && (c.isUncertain() || PLACEHOLDER.containsMatchIn(s) || HEDGE.containsMatchIn(s.trim()) ||
                    (canLookUp && c.tokens >= 2 && c.minProb < LOOKUP_MIN_PROB)) -> Gate.UNSURE
                PLACEHOLDER.containsMatchIn(s) -> if (i == 0) Gate.UNSURE else Gate.TRUNCATE
                // Very low confidence on the opening sentence means the model could not make sense of the input.
                i == 0 && c.tokens >= 3 && c.score < VERY_UNSURE_SCORE -> Gate.UNSURE
                i > 0 && repeat -> Gate.TRUNCATE
                i > 0 && ((factual && c.isUncertain()) || c.score < VERY_UNSURE_SCORE) -> Gate.FINISH
                else -> Gate.SPEAK
            }
        }
        if (!memoryContext.isNullOrBlank()) log("memory: $memoryContext")
        if (!selfState.isNullOrBlank()) log("phone status: $selfState")
        val promptText = prompts.prompt(
            history = turns,
            user = userText,
            selfStateLine = selfState,
            memoryContext = memoryContext,
            extraNudge = offlineNudge?.ifBlank { null },
        )
        val sentPayload = prompts.userPayload(userText, selfState, memoryContext, offlineNudge?.ifBlank { null })
        val t0 = clockMillis()
        // A factual question that may need a lookup: search while the model answers, so an unsure answer doesn't wait.
        val prefetch = if (prefetchLookups && factual && canLookUp) {
            lookupScope.async { runCatching { tools.run(ToolCall("web_search", mapOf("query" to searchQuery(userText, turns)))) }.getOrNull() }
        } else {
            null
        }
        val firstRun = streamSayOrCollectTool(
            engine = engine,
            prompt = promptText,
            grammar = prompts.grammar(),
            maxTokens = maxTokens,
            sampling = sampling,
            gate = gate,
            onSentence = onSentence,
            earlyUnsureLogProb = if (factual && canLookUp) kotlin.math.ln(LOOKUP_MIN_PROB) else null,
        )
        if (!firstRun.abortedUnsure) prefetch?.cancel()

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
            answerUnsure(userText, heardConfidence, factual, offline = offlineNudge != null, turns = turns, onSentence = onSentence, prefetched = prefetch)
            return
        }
        when (parsed) {
            is ModelOutput.Say -> {
                val replyText = if (firstRun.truncated || firstRun.spokeAnything) firstRun.spokenText.ifBlank { stripPromptNotes(parsed.text) } else stripPromptNotes(parsed.text)
                if (firstRun.spokeAnything) {
                    // Already said out loud: a retry would only talk over it. Keep what was spoken.
                    val echoed = EchoGuard.isEcho(userText, replyText) || repeatsHistory(replyText, turns, userText)
                    if (!echoed) recordTurn(userText, "SAY: $replyText", sent = sentPayload)
                    onEpisodeComplete(userText, replyText, null, echoed)
                    return
                }
                val repeats = repeatsHistory(replyText, turns, userText)
                val echoed = firstRun.abortedForEcho || replyText.isBlank() || EchoGuard.isEcho(userText, replyText) || repeats
                if (!echoed) {
                    recordTurn(userText, "SAY: $replyText", sent = sentPayload)
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
                        answerUnsure(userText, heardConfidence, factual, offline = offlineNudge != null, turns = turns, onSentence = onSentence, prefetched = prefetch)
                        return
                    }
                    val retryParsed = parseModelOutput(retryRun.raw)
                    val retryText = if (retryRun.truncated) retryRun.spokenText else (retryParsed as? ModelOutput.Say)?.text.orEmpty()
                    if (!retryRun.abortedForEcho && retryText.isNotBlank() &&
                        !EchoGuard.isEcho(userText, retryText) && !repeatsHistory(retryText, turns, userText)
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
        /** Stop as soon as the first sentence has a token below this log-prob (it would be withheld anyway). */
        earlyUnsureLogProb: Float? = null,
    ): StreamResult = coroutineScope {
        val raw = StringBuilder()
        val stoppedUnsure = AtomicBoolean(false)
        var firstSentenceTokens = 0
        var firstSentenceMin = Float.POSITIVE_INFINITY
        var sentencesEmitted = 0
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
            sentencesEmitted++
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
                    // Punctuation-only pieces don't count: Gemma sometimes picks "’" where it rates "'" far likelier,
                    // and that near-zero probability made "The sky’" look like a guess and cut the answer.
                    currentLp = if (piece.any { it.isLetterOrDigit() }) engine.lastTokenLogProb else Float.NaN
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
                    if (earlyUnsureLogProb != null && mode == StreamMode.SAY && sentencesEmitted == 0 && !currentLp.isNaN()) {
                        firstSentenceTokens++
                        firstSentenceMin = minOf(firstSentenceMin, currentLp)
                        if (firstSentenceTokens >= 2 && firstSentenceMin < earlyUnsureLogProb) {
                            stoppedUnsure.set(true)
                            return@generate false
                        }
                    }
                    !cancelledByConsumer.get()
                }

                if (!cancelledByConsumer.get() && !stoppedUnsure.get() && mode == StreamMode.UNKNOWN) {
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
                if (!cancelledByConsumer.get() && !stoppedUnsure.get() && mode == StreamMode.SAY) {
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
        // Set once an unsure clause arrives mid-sentence: speak on to the end of that sentence, then stop.
        var finishing = false
        try {
            for ((rawSentence, conf) in sentences) {
                seen.add(conf)
                // The small model sometimes reads out the bracketed context lines of its prompt; never speak those.
                val sentence = stripPromptNotes(rawSentence)
                if (sentence.isEmpty()) continue
                // Judge the sentence with its "[Name]"-style slots still in place; speak it without brackets.
                val judged = rawSentence.replace(PROMPT_NOTE, " ").replace(UNCLOSED_NOTE, " ").trim()
                var decision = if (finishing) Gate.SPEAK else gate?.invoke(index, judged, conf) ?: Gate.SPEAK
                index++
                if (decision == Gate.FINISH) {
                    if (spoken.isNotEmpty() && !endsSentence(spoken.last())) {
                        log("sentence ${index - 1} finishes the sentence, then stops: \"${sentence.take(80)}\" (${conf.describe()})")
                        finishing = true
                        decision = Gate.SPEAK
                    } else {
                        decision = Gate.TRUNCATE
                    }
                }
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
                if (finishing && endsSentence(sentence)) {
                    truncated = true
                    cancelledByConsumer.set(true)
                    break
                }
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
        if (stoppedUnsure.get() && !spokeAnything) {
            abortedUnsure = true
            log("first sentence unsure mid-way (min log-prob %.2f); stopped early".format(java.util.Locale.US, firstSentenceMin))
        }
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

    /**
     * True when [reply] is a near-duplicate of an earlier assistant turn in [turns] that answered a different
     * question. The same answer to the same question again is fine.
     */
    private fun repeatsHistory(reply: String, turns: List<Turn>, userText: String? = null): Boolean {
        val norm = normalizeForRepeat(reply)
        if (norm.isEmpty()) return false
        val askedNow = userText?.let(::normalizeForRepeat)
        return turns.any { turn ->
            if (askedNow != null && normalizeForRepeat(turn.user) == askedNow) return@any false
            val prev = stripSayPrefix(turn.assistant)
            // Same shape but new facts ("...Germany is Berlin" after "...France is Paris") is a new answer.
            normalizeForRepeat(prev) == norm || (EchoGuard.isEcho(prev, reply) && (contentWords(reply) - contentWords(prev)).isEmpty())
        }
    }

    private fun contentWords(text: String): Set<String> =
        normalizeForRepeat(text).split(' ').filter { it.length > 2 && it !in REPEAT_STOP_WORDS }.toSet()

    private fun normalizeForRepeat(text: String): String =
        stripSayPrefix(text).lowercase()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun stripSayPrefix(text: String): String {
        val trimmed = text.trimStart()
        return if (trimmed.startsWith("SAY:", ignoreCase = true)) trimmed.substring(4).trimStart() else trimmed
    }

    private fun stripPromptNotes(text: String): String =
        text.replace(PROMPT_NOTE, " ").replace(UNCLOSED_NOTE, " ").replace(Regex("""\[[^\[\]]*\[[^\]]*]+"""), " ")
            .replace(Regex("""[\[\]]"""), " ").replace(Regex("\\s+"), " ").replace(Regex("\\s+([.,!?'])"), "$1").trim().let { if (it.any(Char::isLetterOrDigit)) it else "" }

    /** A lone word that is not a greeting or a short reply is almost always a clipped or misheard request. */
    private fun closingReply(text: String): String? {
        val t = text.trim().trimEnd('.', '!', '?')
        return when {
            THANKS.matches(t) -> "You're welcome."
            GOODBYE.matches(t) -> "Goodbye!"
            DISMISS.matches(t) -> "Okay."
            else -> null
        }
    }

    private fun looksGarbled(text: String): Boolean {
        val words = text.lowercase().replace(Regex("[^\\p{L}\\p{N}\\s']"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size >= 2 && words.all { it.length <= 2 }) return true // "S. L. M."
        return words.size == 1 && words[0] !in SINGLE_WORD_OK
    }

    /**
     * The model could not answer confidently. A factual question that was heard clearly is looked up online and
     * answered from the search result; otherwise Jarvis says it is not sure (or asks to repeat a non-question).
     */
    private suspend fun answerUnsure(
        userText: String,
        heardConfidence: Float,
        factual: Boolean,
        offline: Boolean,
        turns: List<Turn>,
        onSentence: suspend (String) -> Unit,
        prefetched: Deferred<ToolResult?>? = null,
    ) {
        if (factual && !offline && heardConfidence >= HEARD_UNSURE && tools.has("web_search")) {
            val call = ToolCall("web_search", mapOf("query" to searchQuery(userText, turns)))
            onLookupStarted?.invoke()
            val result = prefetched?.let { runCatching { it.await() }.getOrNull() } ?: runCatching { tools.run(call) }.getOrNull()
            if (result != null && result.ok && result.spoken.isNotBlank()) {
                if (onLookupStarted == null) onSentence(LOOKING_UP_MSG)
                val answer = groundedAnswer(userText, result, turns, onSentence)
                recordTurn(userText, "SAY: $answer", sent = lastGroundedSent)
                onEpisodeComplete(userText, answer, call, false)
                return
            }
            log("web lookup failed: ${result?.spoken}")
            onSentence(NOT_FOUND_MSG)
            onEpisodeComplete(userText, NOT_FOUND_MSG, call, false)
            return
        }
        val msg = if (factual) unsureReply(heardConfidence, offline) else DIDNT_CATCH_MSG
        onSentence(msg)
        // Not recorded in history: the model copied "I'm not sure..." into later, unrelated answers.
        onEpisodeComplete(userText, msg, null, false)
    }

    /**
     * What to search for: the question as asked, joined to the previous question when it only makes sense with it
     * ("And what about Germany?" after "What is the capital of France?").
     */
    internal fun searchQuery(userText: String, turns: List<Turn>): String {
        fun leans(q: String) = ELLIPTICAL_FOLLOW_UP.containsMatchIn(q.trim()) || REFERS_BACK.containsMatchIn(q)
        if (!leans(userText)) return userText
        // The topic is the last question that names it: "When was it first sung" after "Tell me more about it" after
        // "Who wrote the Indian national anthem?" searches with the anthem question.
        val topic = turns.asReversed().map { it.user.trim() }.firstOrNull { it.isNotEmpty() && !leans(it) }
            ?: turns.lastOrNull()?.user?.trim().orEmpty()
        return if (topic.isNotEmpty()) "$topic $userText" else userText
    }

    /** Answers [userText] from a web search result: the model reads the snippets and replies in a sentence or two. */
    private suspend fun groundedAnswer(
        userText: String,
        result: ToolResult,
        turns: List<Turn>,
        onSentence: suspend (String) -> Unit,
    ): String {
        val question = searchQuery(userText, turns)
        val evidence = selectEvidence(question, result.feedToModel ?: result.spoken)
        log("web evidence: \"${evidence.take(200)}\"")
        if (!evidenceIsRelevant(question, evidence)) {
            // A pop-song article for an anthem question was turned into "It was first sung in 1987".
            log("web evidence is off-topic; not answering from it")
            onSentence(NOT_FOUND_MSG)
            return NOT_FOUND_MSG
        }
        if (llm?.isLoaded != true) {
            onSentence(result.spoken)
            return result.spoken
        }
        lastGroundedSent = prompts.userPayload(userText, memoryContext = "Web search: $evidence")
        // The small model fills gaps with guesses ("Australia won" from snippets that never name the winner), so every
        // name or number it says must appear in the search text or the question.
        val run = streamSayOrCollectTool(
            engine = llm,
            prompt = prompts.prompt(history = turns, user = userText, memoryContext = "Web search: $evidence"),
            grammar = prompts.sayOnlyGrammar(),
            maxTokens = replyMaxTokens(),
            sampling = SamplingConfig(temperature = 0.2f, repeatPenalty = 1.1f),
            gate = { i, s, _ ->
                // Only answers that are a name, place, date or number: "why" answers may rightly add a term
                // ("Rayleigh scattering") the snippet doesn't use.
                val madeUp = if (ASKS_FOR_NAME.containsMatchIn(userText)) ungroundedName(s, question, evidence) else null
                if (madeUp == null) {
                    Gate.SPEAK
                } else {
                    log("web answer names \"$madeUp\", which the search text doesn't")
                    if (i == 0) Gate.REJECT else Gate.TRUNCATE
                }
            },
            onSentence = onSentence,
        )
        if (run.spokeAnything) return run.spokenText
        if (run.abortedForEcho) {
            onSentence(NOT_FOUND_MSG)
            return NOT_FOUND_MSG
        }
        val text = (parseModelOutput(run.raw) as? ModelOutput.Say)?.text?.let(::stripPromptNotes).orEmpty()
        if (text.isNotBlank()) return text
        onSentence(result.spoken)
        return result.spoken
    }

    /**
     * The sentences of [text] that share the most key words with [query], in their original order, within [maxChars]:
     * the answer is often in a later snippet, and the first [maxChars] alone cut it off.
     */
    internal fun selectEvidence(query: String, text: String, maxChars: Int = MAX_EVIDENCE_CHARS): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= maxChars) return clean
        val keys = queryKeys(query)
        val parts = clean.split(Regex("""\s*\|\s*|(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val patterns = keys.associateWith { k ->
            listOf(Regex("\\b" + Regex.escape(k.take(5)))) + KEY_ALIASES[k].orEmpty()
        }
        val lower = parts.map { it.lowercase() }
        fun has(i: Int, k: String) = patterns.getValue(k).any { it.containsMatchIn(lower[i]) }
        // Words of the question that few sentences share weigh most: "won" picks out the result, while "World Cup"
        // is in every sentence about the tournament.
        val weight = keys.associateWith { k ->
            val df = parts.indices.count { has(it, k) }
            if (df == 0) 0.0 else ln((parts.size + 1.0) / df)
        }
        val score = parts.indices.map { i -> keys.filter { has(i, it) }.sumOf { weight.getValue(it) } }
        // Per character, so one long sentence that only names the topic doesn't crowd out the answer.
        val ranked = parts.indices.filter { score[it] > 0 }.sortedByDescending { score[it] / maxOf(parts[it].length, 60) }
        val chosen = sortedSetOf<Int>()
        var length = 0
        for (i in ranked) {
            val add = parts[i].length + if (chosen.isEmpty()) 0 else 1
            if (length + add > maxChars) continue
            chosen += i
            length += add
        }
        return if (chosen.isEmpty()) clean.take(maxChars) else chosen.joinToString(" ") { parts[it] }
    }

    /**
     * A name or number in [answer] that neither [evidence] nor [question] contains, or null when all are backed.
     * Names match by their first five letters, so "Indian" is backed by "India".
     */
    internal fun ungroundedName(answer: String, question: String, evidence: String): String? {
        val asked = question.lowercase().replace('’', '\'')
        val source = evidence.lowercase().replace('’', '\'')
        val digits = "$asked $source".replace(",", "")
        fun inText(word: String, text: String): Boolean {
            val lower = word.lowercase().replace('’', '\'')
            return Regex("\\b" + Regex.escape(lower.take(5))).containsMatchIn(text)
        }
        // Adjacent capitalised words are one name ("Rabindranath Tagore"): backed if any of its words is.
        val name = ArrayList<String>()
        var nameEnd = -1
        // A name is made up unless it only repeats the question, or a word of it beyond the question is in the search
        // text: "Prime Minister Fumio Kishida" is not backed by the question's "prime minister".
        fun nameIsMadeUp(): Boolean {
            val words = name.filter { it.lowercase().replace('’', '\'') !in COMMON_CAPITALISED }
            val beyondQuestion = words.filterNot { inText(it, asked) }
            return beyondQuestion.isNotEmpty() && beyondQuestion.none { inText(it, source) }
        }
        for (m in NAME_OR_NUMBER.findAll(answer)) {
            val word = m.value
            if (word.first().isDigit()) {
                if (!Regex("(?<![0-9])" + Regex.escape(word.replace(",", "")) + "(?![0-9])").containsMatchIn(digits)) return word
                continue
            }
            if (name.isNotEmpty() && answer.substring(nameEnd, m.range.first) != " ") {
                if (nameIsMadeUp()) return name.joinToString(" ")
                name.clear()
            }
            name += word
            nameEnd = m.range.last + 1
        }
        return if (name.isNotEmpty() && nameIsMadeUp()) name.joinToString(" ") else null
    }

    /** Search results must share key words with the question, or they answer a different question. */
    internal fun evidenceIsRelevant(query: String, evidence: String): Boolean {
        val keys = queryKeys(query)
        if (keys.isEmpty()) return true
        val text = evidence.lowercase()
        // Stem match ("india" in "indian", "anthem" in "anthems"): first 5 letters at a word start.
        val hits = keys.count { Regex("\\b" + Regex.escape(it.take(5))).containsMatchIn(text) }
        return hits >= 1
    }

    private fun queryKeys(query: String): Set<String> =
        query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in QUERY_STOP_WORDS }.toSet()

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

    /** The user message [groundedAnswer] last sent (question + web evidence), for [Turn.sent]. */
    @Volatile private var lastGroundedSent: String? = null

    private fun activeHistory(): List<Turn> = synchronized(historyLock) { history.toList() }

    private fun recordTurn(user: String, assistant: String, sent: String? = null) {
        val now = clockMillis()
        synchronized(historyLock) {
            history.add(Turn(user = user, assistant = assistant, sent = sent))
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

    private fun endsSentence(text: String): Boolean = SENTENCE_END.containsMatchIn(text)

    companion object {
        const val HISTORY_IDLE_RESET_MS = 900_000L
        const val NO_MODEL_MSG = "My language model isn't loaded, so I can only handle basic commands."
        const val OVERHEATING_MSG = "I'm overheating, so I'll keep to basic commands."
        const val ECHO_FALLBACK_MSG = "I'm not sure how to answer that yet."
        /** Below this, speech recognition probably got words wrong: ask the user to repeat rather than say "not sure". */
        private const val HEARD_UNSURE = 0.5f
        const val DIDNT_CATCH_MSG = "Sorry, I didn't catch that. Could you say it again?"
        const val LOOKING_UP_MSG = "Let me look that up."
        const val NOT_FOUND_MSG = "Sorry, I couldn't find that online."
        /** Search text handed to the model: enough for an answer, short enough to prefill quickly on the phone. */
        private const val MAX_EVIDENCE_CHARS = 420
        /** Questions whose answer is a name, place, date or number: a web answer to these may only use names it read. */
        private val ASKS_FOR_NAME = Regex(
            """\b(who|whom|whose|which|when|where|how (many|much|old|long|tall|far|big)|what (year|date|day|time))\b""",
            RegexOption.IGNORE_CASE,
        )
        /** A question's "won" is answered by "India claimed the victory ... to win". */
        private val KEY_ALIASES: Map<String, List<Regex>> = listOf(
            Regex("""\bwins?\b"""), Regex("""\bwon\b"""), Regex("""\bvictor"""), Regex("""\bbeat""")
        ).let { outcome -> mapOf("won" to outcome, "win" to outcome, "wins" to outcome, "winner" to outcome) }
        /** Capitalised words and numbers in an answer: the claims a web answer must take from the search text. */
        private val NAME_OR_NUMBER = Regex("""\b(?:\p{Lu}[\p{L}'’-]*|\d(?:[\d,.]*\d)?)""")
        /** Capitalised only because they start a sentence: not claims. */
        private val COMMON_CAPITALISED = setOf(
            "the", "a", "an", "it", "it's", "its", "this", "that", "these", "those", "he", "she", "they", "we", "you", "i",
            "i'm", "in", "on", "at", "for", "of", "yes", "no", "according", "sorry", "there", "their", "his", "her", "as",
            "by", "from", "with", "and", "but", "so", "while", "during", "after", "before", "today", "currently", "also",
            "however", "both", "sure", "my", "our", "if", "when", "what", "who", "where", "which", "unfortunately",
        )
        /**
         * Factual answers with a token below this are looked up when online. Wrong names on the phone: 0.12-0.35
         * (Gemma's "Jana Samata" anthem: 0.33); a right "Paris." came in at 0.42, so 0.45 sent it to a needless lookup.
         */
        private const val LOOKUP_MIN_PROB = 0.40f
        private val REPEAT_STOP_WORDS = setOf("the", "and", "for", "are", "was", "were", "has", "have", "its", "his", "her", "this", "that", "with", "from", "also", "you", "your")
        /** Questions whose answer changes over time: the model's training data is stale, so these are searched first. */
        private val TIME_SENSITIVE = Regex(
            """\b(current(ly)?|now|today|latest|recent|this (year|week|month)|president|prime minister|chief minister|ceo|captain|governor|mayor|price|score|news|won|winner|election)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val EXPLICIT_SEARCH = Regex(
            """^(search|look up|google|find online|tell me about)\b""",
            RegexOption.IGNORE_CASE,
        )
        /** A follow-up question that leans on the previous one. */
        private val ELLIPTICAL_FOLLOW_UP = Regex(
            """^(and|what about|how about|and what about|what of|and the|same for|also)\b""",
            RegexOption.IGNORE_CASE,
        )
        /** "Tell me one more fact about it": the topic is in the previous question (it found a random fun-facts page). */
        private val QUERY_STOP_WORDS = setOf(
            "who", "what", "when", "where", "why", "how", "which", "whose", "the", "and", "was", "were", "is", "are", "did",
            "does", "do", "for", "about", "tell", "me", "more", "one", "fact", "facts", "its", "it's", "that", "this", "his",
            "her", "their", "them", "they", "him", "she", "can", "you", "your", "with", "from", "into", "first", "some", "any",
            "much", "many", "long", "old", "has", "have", "had", "been", "of", "in", "on", "to", "a",
        )
        private val REFERS_BACK = Regex("""\b(it|its|it's|that|this|those|these|he|she|him|her|his|they|them|their)\b""", RegexOption.IGNORE_CASE)
        /** The model saying it has no answer ("I don't have information about...") — treated like low confidence. */
        private val HEDGE = Regex(
            """^(i'?m not sure|i am not sure|i don'?t (have|know)|i do not (have|know)|i'?m (unable|not able)|i cannot|i can'?t (find|access|provide|tell))\b""",
            RegexOption.IGNORE_CASE,
        )
        private val PROMPT_NOTE = Regex("""\[(?:Rule|Memory|Phone status|Battery)[^\]]*]""", RegexOption.IGNORE_CASE)
        private val UNCLOSED_NOTE = Regex("""\[(?:Rule|Memory|Phone status|Battery)[^\]]*$""", RegexOption.IGNORE_CASE)
        /** Template slots like "[Name]" mean the model had no real answer. */
        private val PLACEHOLDER = Regex("""\[[A-Za-z ]{2,20}(]|$)""")
        private val HOW_ARE_YOU = Regex("""^(hi |hello |hey )?(jarvis,? )?(how are you|how's it going|how are you doing|how do you do)\b""", RegexOption.IGNORE_CASE)
        const val HOW_ARE_YOU_REPLY = "I'm doing well, thanks for asking. How can I help?"
        private val THANKS = Regex("""(?i)(ok(ay)?[, ]+)?(thanks?( a lot| so much)?|thank you( so much| very much)?)([, ]+jarvis)?""")
        private val GOODBYE = Regex("""(?i)(ok(ay)?[, ]+)?(bye|goodbye|good night|see you)([, ]+jarvis)?""")
        private val DISMISS = Regex("""(?i)(ok(ay)?[, ]+)?(that'?s (all|it)|never ?mind|no thanks?|nothing|cancel|stop)([, ]+jarvis)?""")
        /** Questions about the conversation or Jarvis itself, not facts about the world. */
        private val CHITCHAT = Regex(
            """^(tell me (a|another) (joke|story)|tell me something|say something|sing|how are you|how's it going|how do you do|how have you been|what's up|whats up|what are you doing|who are you|what can you do|what do you think|do you like|are you)\b""",
            RegexOption.IGNORE_CASE,
        )
        /**
         * Questions about Jarvis itself ("Will you be able to dream?", "What are your top five thoughts?") are not looked
         * up: the web answered them with self-help pages. Requests phrased with "you" ("Can you tell me who ...") are not
         * matched.
         */
        internal val ABOUT_JARVIS = Regex(
            """\byour(self|s)?\b|^(?:(?:what|how|why|who|where|when)\s+)?(?:are|were|will|would|do|did|can|could|have|has)\s+you\b(?!\s+(?:tell|give|find|show|look|search|explain|know|remind|set|play|open|call|turn|check|help|define|translate|read|recommend|suggest|name|list|convert|calculate|spell|describe))""",
            RegexOption.IGNORE_CASE,
        )
        private val SINGLE_WORD_OK = setOf(
            "hi", "hello", "hey", "thanks", "thank", "bye", "goodbye", "yes", "no", "okay", "ok", "sure",
            "great", "cool", "nice", "good", "help", "jarvis", "stop", "why", "really", "what",
        )
        /** A later sentence this unsure is cut even in chat (the model is rambling). */
        private const val VERY_UNSURE_SCORE = 0.35f
        /** A full stop, "!" or "?", possibly followed by a closing quote or bracket. */
        private val SENTENCE_END = Regex("""[.!?…]["'”’)\]]*$""")
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
