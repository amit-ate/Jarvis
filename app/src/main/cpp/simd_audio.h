#ifndef JARVIS_SIMD_AUDIO_H
#define JARVIS_SIMD_AUDIO_H

#include <cstdint>
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

// Vectorized NEON PCM16 -> Float32 conversion, software gain, [-1.0, 1.0] clamping, and RMS calculation.
float simd_pcm16_to_float_and_rms(const int16_t *pcm, float *out, int length, float gain);

// Scalar reference implementation for verification and benchmarking.
float scalar_pcm16_to_float_and_rms(const int16_t *pcm, float *out, int length, float gain);

JNIEXPORT jfloat JNICALL
Java_com_jarvis_edge_audio_SimdAudio_nativeProcessPcm(
    JNIEnv *env, jclass, jshortArray jPcm, jint offset, jint length,
    jfloatArray jOut, jfloat gain);

JNIEXPORT jlongArray JNICALL
Java_com_jarvis_edge_audio_SimdAudio_nativeBenchmark(
    JNIEnv *env, jclass, jint iterations, jint frameSize);

#ifdef __cplusplus
}
#endif

#endif // JARVIS_SIMD_AUDIO_H
