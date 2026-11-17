package com.jarvis.edge.audio

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad as SherpaOnnxVad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt

class SileroVad(
    modelFile: File,
    private val threshold: Float = 0.38f,
    numThreads: Int = 1,
    private val minRms: Float = 0.012f,
) : Vad {
    private val vad: SherpaOnnxVad

    init {
        val sileroConfig = SileroVadModelConfig(
            model = modelFile.absolutePath,
            threshold = threshold,
            minSilenceDuration = 0.25f,
            minSpeechDuration = 0.05f,
            windowSize = 512,
            maxSpeechDuration = 15.0f,
        )
        val config = VadModelConfig(
            sileroVadModelConfig = sileroConfig,
            sampleRate = 16_000,
            numThreads = numThreads,
            provider = "cpu",
            debug = false,
        )
        vad = SherpaOnnxVad(null, config)
    }

    override fun isSpeech(frame: FloatArray): Boolean {
        val prob = vad.compute(frame)
        if (prob < threshold || frame.isEmpty()) return false
        var sum = 0.0
        for (s in frame) sum += s * s
        return sqrt(sum / frame.size) >= minRms
    }

    override fun reset() {
        vad.reset()
    }

    fun release() {
        vad.release()
    }
}

class SpeechSegmenter(
    private val vad: Vad,
    private val maxSeconds: Float = 15f,
    private val trailingSilenceMs: Int = 700,
    private val shortTrailingSilenceMs: Int = 450,
    private val noSpeechTimeoutMs: Int = 4000,
) {
    sealed interface Result {
        data object Pending : Result
        data class Done(val samples: FloatArray) : Result
        data object NoSpeech : Result
    }

    private val maxFrames = ceil(maxSeconds * SAMPLES_PER_SECOND / FRAME_SAMPLES).toInt()
    private val trailingSilenceFrames = ceil(trailingSilenceMs / FRAME_MS).toInt()
    private val shortTrailingSilenceFrames = ceil(shortTrailingSilenceMs / FRAME_MS).toInt()
    private val noSpeechFrames = ceil(noSpeechTimeoutMs / FRAME_MS).toInt()

    private val preSpeech = ArrayDeque<FloatArray>(PRE_SPEECH_FRAMES + 1)
    private val speechBuffers = ArrayList<FloatArray>()
    private val pendingSilence = ArrayList<FloatArray>()
    private var heardSpeech = false
    private var totalFrames = 0
    private var consecutiveSilence = 0
    private var totalSampleCount = 0

    fun accept(frame: FloatArray, shortTail: Boolean = false): Result {
        val isSpeech = vad.isSpeech(frame)
        totalFrames++

        if (!heardSpeech) {
            if (!isSpeech) {
                if (preSpeech.size >= PRE_SPEECH_FRAMES) {
                    preSpeech.removeFirst()
                }
                preSpeech.addLast(frame.copyOf())
                consecutiveSilence++
                if (consecutiveSilence >= noSpeechFrames) {
                    reset()
                    return Result.NoSpeech
                }
                return Result.Pending
            }
            heardSpeech = true
            consecutiveSilence = 0
            while (preSpeech.isNotEmpty()) {
                val pre = preSpeech.removeFirst()
                speechBuffers.add(pre)
                totalSampleCount += pre.size
            }
            speechBuffers.add(frame.copyOf())
            totalSampleCount += frame.size
            if (totalFrames >= maxFrames) {
                return finishDone()
            }
            return Result.Pending
        }

        if (isSpeech) {
            for (s in pendingSilence) {
                speechBuffers.add(s)
                totalSampleCount += s.size
            }
            pendingSilence.clear()
            consecutiveSilence = 0
            speechBuffers.add(frame.copyOf())
            totalSampleCount += frame.size
            if (totalFrames >= maxFrames) {
                return finishDone()
            }
            return Result.Pending
        }

        consecutiveSilence++
        pendingSilence.add(frame.copyOf())
        val limit = if (shortTail) shortTrailingSilenceFrames else trailingSilenceFrames
        if (consecutiveSilence >= limit || totalFrames >= maxFrames) {
            return finishDone()
        }
        return Result.Pending
    }

    fun reset() {
        preSpeech.clear()
        speechBuffers.clear()
        pendingSilence.clear()
        heardSpeech = false
        totalFrames = 0
        consecutiveSilence = 0
        totalSampleCount = 0
        vad.reset()
    }

    private fun finishDone(): Result.Done {
        val out = FloatArray(totalSampleCount)
        var pos = 0
        for (chunk in speechBuffers) {
            chunk.copyInto(out, destinationOffset = pos)
            pos += chunk.size
        }
        reset()
        return Result.Done(out)
    }

    companion object {
        private const val SAMPLES_PER_SECOND = 16_000f
        private const val FRAME_SAMPLES = 512
        private const val FRAME_MS = 32f
        private const val PRE_SPEECH_FRAMES = 6
    }
}
