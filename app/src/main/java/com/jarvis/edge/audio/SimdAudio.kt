package com.jarvis.edge.audio

object SimdAudio {
    init {
        System.loadLibrary("llama_jni")
    }

    external fun nativeProcessPcm(
        pcm: ShortArray,
        offset: Int,
        length: Int,
        out: FloatArray,
        gain: Float,
    ): Float

    external fun nativeBenchmark(iterations: Int, frameSize: Int): LongArray

    fun processPcm(
        pcm: ShortArray,
        offset: Int,
        length: Int,
        out: FloatArray,
        gain: Float = 3.0f,
    ): Float {
        return nativeProcessPcm(pcm, offset, length, out, gain)
    }
}
