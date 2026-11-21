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

    suspend fun runTurn(frames: Flow<FloatArray>) {
        setState(AssistantState.LISTENING)
        speaker.clearStop()
        segmenter.reset()
        live.reset()

        try {
            var capturedSamples: FloatArray? = null
            var noSpeech = false

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

            if (noSpeech) {
                pendingConfirmation = null
                live.reset()
                return
            }

            val samples = capturedSamples ?: return
            val finalLive = WakeRemnant.clean(live.finish())
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

                if (arb.needsConfirmation && transcript.length >= 2) {
                    onTranscript(transcript)
                    onTranscriptionDetail(liveRaw, accurateRaw, transcript)
                    pendingConfirmation = PendingConfirmation(
                        heardRaw = liveRaw.ifBlank { accurateRaw },
                        candidate = transcript,
                    )
                    val confirmPrompt = "Did you say $transcript?"
                    setState(AssistantState.SPEAKING)
                    onReplyChunk(confirmPrompt)
                    speaker.speak(confirmPrompt)
                    return
                }
            }

            onTranscript(transcript)
            onTranscriptionDetail(liveRaw, accurateRaw, transcript)

            if (transcript.length < 2) {
                setState(AssistantState.SPEAKING)
                onReplyChunk(SORRY_MSG)
                speaker.speak(SORRY_MSG)
                return
            }

            speakBrainResponse(transcript)
        } catch (_: StopSpeakingSignal) {
            onRespondInterrupted(spokenSoFar.toString())
            // Stop speaking mid-response cleanly and return to IDLE.
        } finally {
            setState(AssistantState.IDLE)
        }
    }

    private suspend fun speakBrainResponse(transcript: String) {
        var speakingStarted = false
        spokenSoFar.setLength(0)
        onRespondStart(transcript)
        brain.respond(transcript) { sentence ->
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

    private class TurnDoneSignal : CancellationException("Turn segmentation finished")
    private class StopSpeakingSignal : CancellationException("Speaker stopped")

    companion object {
        const val SORRY_MSG = "Sorry, I didn't catch that."
        const val REPEAT_MSG = "Okay, please repeat what you meant."
    }
}
