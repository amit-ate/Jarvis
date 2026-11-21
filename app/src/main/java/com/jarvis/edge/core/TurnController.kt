package com.jarvis.edge.core

import com.jarvis.edge.audio.LiveTranscriber
import com.jarvis.edge.audio.Speaker
import com.jarvis.edge.audio.SpeechSegmenter
import com.jarvis.edge.audio.Transcriber
import com.jarvis.edge.audio.TranscriptArbiter
import com.jarvis.edge.audio.WakeRemnant
import com.jarvis.edge.brain.Brain
import com.jarvis.edge.brain.FastIntents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

class TurnController(
    private val segmenter: SpeechSegmenter,
    private val live: LiveTranscriber,
    private val accurate: Transcriber?,
    private val brain: Brain,
    private val speaker: Speaker,
    private val setState: (AssistantState) -> Unit,
    private val preferStreaming: Boolean = false,
    private val onTranscript: (String) -> Unit = {},
    private val onReplyChunk: (String) -> Unit = {},
    private val arbiter: TranscriptArbiter = TranscriptArbiter(),
    private val correctionsProvider: () -> Map<String, String> = { emptyMap() },
    private val onTranscriptionDetail: (liveRaw: String, accurateRaw: String, chosen: String) -> Unit = { _, _, _ -> },
    private val onCorrectionConfirmed: (heard: String, meant: String) -> Unit = { _, _ -> },
    private val onSpeechActivity: () -> Unit = {},
    /** Called just before the brain answers (not for confirmation prompts or "sorry" replies). */
    private val onRespondStart: (transcript: String) -> Unit = {},
    /** Called when the user stops the reply mid-speech; [spokenSoFar] is what was already said. */
    private val onRespondInterrupted: (spokenSoFar: String) -> Unit = {},
    /** The recorded request and the words chosen for it, for keeping voice samples (not called for blank turns). */
    private val onUtterance: (samples: FloatArray, transcript: String) -> Unit = { _, _ -> },
) {
    private val spokenSoFar = StringBuilder()

    private data class PendingConfirmation(
        val heardRaw: String,
        val candidate: String,
    )

    @Volatile
    private var pendingConfirmation: PendingConfirmation? = null

    fun snapshotPending(): Pair<String, String>? =
        pendingConfirmation?.let { it.heardRaw to it.candidate }

    fun restorePending(heard: String, candidate: String) {
        pendingConfirmation = PendingConfirmation(heardRaw = heard, candidate = candidate)
    }

    val hasPendingConfirmation: Boolean
        get() = pendingConfirmation != null

    /**
     * True when the last turn ended with a full answer that invites more conversation, so the mic can stay open for a
     * follow-up without the wake word. False after silence, an interruption, a "sorry, didn't catch that" or a
     * goodbye ("thanks", "that's all", "stop").
     */
    @Volatile
    var invitesFollowUp: Boolean = false
        private set

    /** [followUp]: the mic reopened after an answer, so stray noise ends the conversation quietly. */
    suspend fun runTurn(frames: Flow<FloatArray>, followUp: Boolean = false) {
        invitesFollowUp = false
        setState(AssistantState.LISTENING)
        speaker.clearStop()
        segmenter.reset()
        live.reset()

        try {
            var capturedSamples: FloatArray? = null
            var noSpeech = false
            var finalLive = ""
            // The pre-roll carries the wake word, so "Jarvis" + a pause can close a segment holding nothing else; then
            // keep listening for the actual request instead of answering "didn't catch that" over it.
            var segments = 0
            while (true) {
            capturedSamples = null
            try {
                frames.collect { frame ->
                    val liveText = live.accept(frame)
                    val shortTail = FastIntents.isClosedCommand(liveText)
                    when (val result = segmenter.accept(frame, shortTail = shortTail)) {
                        is SpeechSegmenter.Result.Pending -> Unit
                        is SpeechSegmenter.Result.NoSpeech -> {
                            noSpeech = true
                            throw TurnDoneSignal()
                        }
                        is SpeechSegmenter.Result.Done -> {
                            capturedSamples = result.samples
                            throw TurnDoneSignal()
                        }
                    }
                }
            } catch (_: TurnDoneSignal) {
                // Early exit from infinite mic stream once speech segmentation completes.
            }
            if (noSpeech || capturedSamples == null) break
            val rawLive = live.finish()
            finalLive = WakeRemnant.clean(rawLive)
            segments++
            if (segments < MAX_SEGMENTS && (finalLive.isBlank() || looksLikeWakeTail(finalLive))) {
                segmenter.reset()
                live.reset()
                continue
            }
            break
            }

            if (noSpeech) {
                pendingConfirmation = null
                live.reset()
                return
            }

            val samples = capturedSamples ?: return
            onSpeechActivity()

            setState(AssistantState.THINKING)

            val pending = pendingConfirmation
            if (pending != null) {
                val replyText = if (arbiter.isYes(finalLive) || arbiter.isNo(finalLive) || accurate == null) {
                    finalLive
                } else {
                    val acc = accurate.transcribe(samples).trim()
                    arbiter.arbitrate(finalLive, acc).chosen
                }.trim()

                pendingConfirmation = null
                if (arbiter.isYes(replyText)) {
                    if (pending.heardRaw.isNotBlank() && !pending.heardRaw.equals(pending.candidate, ignoreCase = true)) {
                        onCorrectionConfirmed(pending.heardRaw, pending.candidate)
                    }
                    onTranscript(pending.candidate)
                    onTranscriptionDetail(pending.heardRaw, pending.candidate, pending.candidate)
                    speakBrainResponse(pending.candidate)
                    return
                }
                if (arbiter.isNo(replyText) || replyText.length < 2) {
                    onTranscript(replyText)
                    val msg = REPEAT_MSG
                    setState(AssistantState.SPEAKING)
                    onReplyChunk(msg)
                    speaker.speak(msg)
                    return
                }
                // User moved on to a new command instead of yes/no; treat replyText normally.
            }

            val corrections = correctionsProvider()
            var liveRaw = finalLive
            var accurateRaw = ""
            val transcript: String
            // How sure recognition is of the words: the two recognisers agreeing is the only evidence we have.
            var heardConfidence = 1f

            if (FastIntents.isClosedCommand(finalLive) || accurate == null || (preferStreaming && finalLive.length >= 3)) {
                transcript = arbiter.applyCorrections(finalLive, corrections).trim()
            } else {
                accurateRaw = WakeRemnant.clean(accurate.transcribe(samples))
                val arb = arbiter.arbitrate(
                    live = finalLive,
                    accurate = accurateRaw,
                    corrections = corrections,
                    intentMatcher = { FastIntents.match(it) != null },
                )
                liveRaw = arb.liveRaw
                accurateRaw = arb.accurateRaw
                transcript = arb.chosen.trim()
                // The streaming model is much weaker than the accurate one, so its disagreement is weak evidence:
                // only a missing accurate transcript makes the words really doubtful.
                heardConfidence = when {
                    arb.accurateRaw.isBlank() -> 0.4f
                    arb.wordErrorRate < 0.3f -> 1f
                    else -> 0.8f
                }

                if (arb.needsConfirmation && transcript.length >= 2) {
                    onTranscript(transcript)
                    onTranscriptionDetail(liveRaw, accurateRaw, transcript)
                    pendingConfirmation = PendingConfirmation(
                        heardRaw = liveRaw.ifBlank { accurateRaw },
                        candidate = transcript,
                    )
                    val confirmPrompt = "Did you say ${transcript.trimEnd('.', '?', '!', ',')}?"
                    setState(AssistantState.SPEAKING)
                    onReplyChunk(confirmPrompt)
                    speaker.speak(confirmPrompt)
                    return
                }
            }

            onTranscript(transcript)
            onTranscriptionDetail(liveRaw, accurateRaw, transcript)
            if (transcript.length >= 2) onUtterance(samples, transcript)

            if (transcript.length < 2) {
                if (followUp) return
                setState(AssistantState.SPEAKING)
                onReplyChunk(SORRY_MSG)
                speaker.speak(SORRY_MSG)
                return
            }

            speakBrainResponse(transcript, heardConfidence)
            invitesFollowUp = !ENDS_CONVERSATION.containsMatchIn(transcript.trim())
        } catch (_: StopSpeakingSignal) {
            onRespondInterrupted(spokenSoFar.toString())
            // Stop speaking mid-response cleanly and return to IDLE.
        } finally {
            setState(AssistantState.IDLE)
        }
    }

    private suspend fun speakBrainResponse(transcript: String, heardConfidence: Float = 1f) {
        var speakingStarted = false
        spokenSoFar.setLength(0)
        onRespondStart(transcript)
        brain.respond(transcript, heardConfidence) { sentence ->
            if (speaker.isStopped) {
                throw StopSpeakingSignal()
            }
            if (!speakingStarted) {
                setState(AssistantState.SPEAKING)
                speakingStarted = true
            }
            onReplyChunk(sentence)
            spokenSoFar.append(sentence).append(' ')
            speaker.speak(sentence)
            if (speaker.isStopped) {
                throw StopSpeakingSignal()
            }
        }
    }

    /** A lone fragment such as "USH" or "VIS" left from the wake word, not a request. */
    private fun looksLikeWakeTail(text: String): Boolean {
        val words = text.lowercase().replace(Regex("[^a-z\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.size == 1 && words[0].length <= 4 && words[0] !in SHORT_REQUESTS
    }

    /**
     * Records one utterance for the voice-training screen without transcribing or answering it: null when nothing
     * was said before the no-speech timeout.
     */
    suspend fun captureUtterance(frames: Flow<FloatArray>): FloatArray? {
        setState(AssistantState.LISTENING)
        segmenter.reset()
        var captured: FloatArray? = null
        try {
            frames.collect { frame ->
                when (val result = segmenter.accept(frame, shortTail = false)) {
                    is SpeechSegmenter.Result.Pending -> Unit
                    is SpeechSegmenter.Result.NoSpeech -> throw TurnDoneSignal()
                    is SpeechSegmenter.Result.Done -> {
                        captured = result.samples
                        throw TurnDoneSignal()
                    }
                }
            }
        } catch (_: TurnDoneSignal) {
            // Segment complete (or no speech).
        }
        return captured
    }

    private class TurnDoneSignal : CancellationException("Turn segmentation finished")
    private class StopSpeakingSignal : CancellationException("Speaker stopped")

    companion object {
        const val SORRY_MSG = "Sorry, I didn't catch that."
        const val REPEAT_MSG = "Okay, please repeat what you meant."
        private const val MAX_SEGMENTS = 2
        private val SHORT_REQUESTS = setOf("yes", "no", "hi", "hey", "ok", "okay", "stop", "why", "what", "how", "who", "bye", "help", "time", "nope", "yeah", "sure")
        /** Things people say to close a conversation: no follow-up listening after these. */
        val ENDS_CONVERSATION = Regex(
            """^([a-z]{1,2}[ ,.]+)?(ok(ay)?[, ]+)?(thanks?( you)?|thank you( jarvis)?|that'?s (all|it)|bye|goodbye|good night|stop|cancel|never ?mind|no thanks?|nothing)\b[ .!,]*(jarvis)?[ .!]*$""",
            RegexOption.IGNORE_CASE,
        )
    }
}
