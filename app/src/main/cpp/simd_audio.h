#ifndef JARVIS_SIMD_AUDIO_H
#define JARVIS_SIMD_AUDIO_H

#include <cstdint>
#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

// Vectorized NEON PCM16 -> Float32 conversion, software gain, [-1.0, 1.0] clamping, and RMS calculation.
float simd_pcm16_to_float_and_rms(const int16_t * __restrict__ pcm, float * __restrict__ out, int length, float gain);

// Scalar reference implementation for verification and benchmarking.
float scalar_pcm16_to_float_and_rms(const int16_t * __restrict__ pcm, float * __restrict__ out, int length, float gain);

// Vectorized NEON DC-offset removal, peak normalization, and [-1.0, 1.0] clamping.
void simd_remove_dc_and_normalize(float * __restrict__ samples, int length, float target_peak, float max_gain);

JNIEXPORT jfloat JNICALL
Java_com_jarvis_edge_audio_SimdAudio_nativeProcessPcm(
    JNIEnv *env, jclass, jshortArray jPcm, jint offset, jint length,
    jfloatArray jOut, jfloat gain);

JNIEXPORT jlongArray JNICALL
Java_com_jarvis_edge_audio_SimdAudio_nativeBenchmark(
    JNIEnv *env, jclass, jint iterations, jint frameSize);

JNIEXPORT void JNICALL
Java_com_jarvis_edge_audio_SimdAudio_nativeNormalizeUtterance(
    JNIEnv *env, jclass, jfloatArray jSamples, jfloat targetPeak, jfloat maxGain);

JNIEXPORT void JNICALL
Java_com_jarvis_edge_audio_SimdAudio_nativePinCurrentThreadToLittleCores(
    JNIEnv *env, jclass);

#ifdef __cplusplus
}
#endif

#endif // JARVIS_SIMD_AUDIO_H
