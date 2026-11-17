package com.jarvis.edge.core

import com.jarvis.edge.audio.LiveTranscriber
import com.jarvis.edge.audio.Speaker
import com.jarvis.edge.audio.SpeechSegmenter
import com.jarvis.edge.audio.Transcriber
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
    private val onTranscript: (String) -> Unit = {},
    private val onReplyChunk: (String) -> Unit = {},
) {
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
                live.reset()
                return
            }

            val samples = capturedSamples ?: return
            val finalLive = live.finish()

            setState(AssistantState.THINKING)

            val transcript = if (FastIntents.isClosedCommand(finalLive) || accurate == null) {
                finalLive
            } else {
                accurate.transcribe(samples)
            }.trim()

            onTranscript(transcript)

            if (transcript.length < 2) {
                setState(AssistantState.SPEAKING)
                onReplyChunk(SORRY_MSG)
                speaker.speak(SORRY_MSG)
                return
            }

            var speakingStarted = false
            brain.respond(transcript) { sentence ->
                if (speaker.isStopped) {
                    throw StopSpeakingSignal()
                }
                if (!speakingStarted) {
                    setState(AssistantState.SPEAKING)
                    speakingStarted = true
                }
                onReplyChunk(sentence)
                speaker.speak(sentence)
                if (speaker.isStopped) {
                    throw StopSpeakingSignal()
                }
            }
        } catch (_: StopSpeakingSignal) {
            // Stop speaking mid-response cleanly and return to IDLE.
        } finally {
            setState(AssistantState.IDLE)
        }
    }

    private class TurnDoneSignal : CancellationException("Turn segmentation finished")
    private class StopSpeakingSignal : CancellationException("Speaker stopped")

    companion object {
        const val SORRY_MSG = "Sorry, I didn't catch that."
    }
}
