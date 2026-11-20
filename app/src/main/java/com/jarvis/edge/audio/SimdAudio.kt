package com.jarvis.edge.audio

import kotlin.math.abs

object SimdAudio {
    private val jniLoaded: Boolean = try {
        System.loadLibrary("llama_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    external fun nativeProcessPcm(
        pcm: ShortArray,
        offset: Int,
        length: Int,
        out: FloatArray,
        gain: Float,
    ): Float

    external fun nativeBenchmark(iterations: Int, frameSize: Int): LongArray

    external fun nativeNormalizeUtterance(
        samples: FloatArray,
        targetPeak: Float,
        maxGain: Float,
    )

    external fun nativePinCurrentThreadToLittleCores()

    fun processPcm(
        pcm: ShortArray,
        offset: Int,
        length: Int,
        out: FloatArray,
        gain: Float = 3.0f,
    ): Float {
        return nativeProcessPcm(pcm, offset, length, out, gain)
    }

    fun normalizeUtterance(
        samples: FloatArray,
        targetPeak: Float = 0.85f,
        maxGain: Float = 4.0f,
    ): FloatArray {
        if (samples.isEmpty()) return samples
        if (jniLoaded) {
            nativeNormalizeUtterance(samples, targetPeak, maxGain)
        } else {
            normalizeUtteranceKotlin(samples, targetPeak, maxGain)
        }
        return samples
    }

    fun normalizeUtteranceKotlin(
        samples: FloatArray,
        targetPeak: Float = 0.85f,
        maxGain: Float = 4.0f,
    ): FloatArray {
        if (samples.isEmpty()) return samples
        var sum = 0.0
        for (s in samples) {
            sum += s.toDouble()
        }
        val mean = (sum / samples.size).toFloat()

        var peak = 0.0f
        for (i in samples.indices) {
            val centered = samples[i] - mean
            samples[i] = centered
            val a = abs(centered)
            if (a > peak) peak = a
        }

        if (peak > 0.005f) {
            val gain = minOf(targetPeak / peak, maxGain)
            for (i in samples.indices) {
                samples[i] = (samples[i] * gain).coerceIn(-1.0f, 1.0f)
            }
        }
        return samples
    }

    fun pinCurrentThreadToLittleCores() {
        if (jniLoaded) {
            nativePinCurrentThreadToLittleCores()
        }
    }
}
