#include "simd_audio.h"

#include <cmath>
#include <chrono>
#include <vector>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

float simd_pcm16_to_float_and_rms(const int16_t * __restrict__ pcm, float * __restrict__ out, int length, float gain) {
  const float scale = gain * (1.0f / 32768.0f);
  float sum_sq = 0.0f;
  int i = 0;

#if defined(__ARM_NEON)
  const float32x4_t v_scale = vdupq_n_f32(scale);
  const float32x4_t v_min = vdupq_n_f32(-1.0f);
  const float32x4_t v_max = vdupq_n_f32(1.0f);
  float32x4_t v_sum_sq0 = vdupq_n_f32(0.0f);
  float32x4_t v_sum_sq1 = vdupq_n_f32(0.0f);
  float32x4_t v_sum_sq2 = vdupq_n_f32(0.0f);
  float32x4_t v_sum_sq3 = vdupq_n_f32(0.0f);

  // Process 16 samples (32 bytes input -> 64 bytes output) per iteration
  const int simd_len16 = length - (length % 16);
  for (; i < simd_len16; i += 16) {
    int16x8_t s16_0 = vld1q_s16(pcm + i);
    int16x8_t s16_1 = vld1q_s16(pcm + i + 8);

    int32x4_t s32_0 = vmovl_s16(vget_low_s16(s16_0));
    int32x4_t s32_1 = vmovl_high_s16(s16_0);
    int32x4_t s32_2 = vmovl_s16(vget_low_s16(s16_1));
    int32x4_t s32_3 = vmovl_high_s16(s16_1);

    float32x4_t f0 = vcvtq_f32_s32(s32_0);
    float32x4_t f1 = vcvtq_f32_s32(s32_1);
    float32x4_t f2 = vcvtq_f32_s32(s32_2);
    float32x4_t f3 = vcvtq_f32_s32(s32_3);

    f0 = vmulq_f32(f0, v_scale);
    f1 = vmulq_f32(f1, v_scale);
    f2 = vmulq_f32(f2, v_scale);
    f3 = vmulq_f32(f3, v_scale);

    f0 = vminq_f32(vmaxq_f32(f0, v_min), v_max);
    f1 = vminq_f32(vmaxq_f32(f1, v_min), v_max);
    f2 = vminq_f32(vmaxq_f32(f2, v_min), v_max);
    f3 = vminq_f32(vmaxq_f32(f3, v_min), v_max);

    vst1q_f32(out + i, f0);
    vst1q_f32(out + i + 4, f1);
    vst1q_f32(out + i + 8, f2);
    vst1q_f32(out + i + 12, f3);

    v_sum_sq0 = vfmaq_f32(v_sum_sq0, f0, f0);
    v_sum_sq1 = vfmaq_f32(v_sum_sq1, f1, f1);
    v_sum_sq2 = vfmaq_f32(v_sum_sq2, f2, f2);
    v_sum_sq3 = vfmaq_f32(v_sum_sq3, f3, f3);
  }

  // Handle remaining 8 samples if any
  if (i + 8 <= length) {
    int16x8_t s16 = vld1q_s16(pcm + i);
    int32x4_t s32_lo = vmovl_s16(vget_low_s16(s16));
    int32x4_t s32_hi = vmovl_high_s16(s16);

    float32x4_t f_lo = vcvtq_f32_s32(s32_lo);
    float32x4_t f_hi = vcvtq_f32_s32(s32_hi);

    f_lo = vmulq_f32(f_lo, v_scale);
    f_hi = vmulq_f32(f_hi, v_scale);

    f_lo = vminq_f32(vmaxq_f32(f_lo, v_min), v_max);
    f_hi = vminq_f32(vmaxq_f32(f_hi, v_min), v_max);

    vst1q_f32(out + i, f_lo);
    vst1q_f32(out + i + 4, f_hi);

    v_sum_sq0 = vfmaq_f32(v_sum_sq0, f_lo, f_lo);
    v_sum_sq1 = vfmaq_f32(v_sum_sq1, f_hi, f_hi);
    i += 8;
  }

  // Horizontal reduction across the 4 independent accumulators
  float32x4_t v_sum_total = vaddq_f32(vaddq_f32(v_sum_sq0, v_sum_sq1),
                                      vaddq_f32(v_sum_sq2, v_sum_sq3));
  sum_sq = vaddvq_f32(v_sum_total);
#endif

  // Remainder scalar handling (< 8 samples)
  for (; i < length; ++i) {
    float f = static_cast<float>(pcm[i]) * scale;
    if (f < -1.0f) f = -1.0f;
    else if (f > 1.0f) f = 1.0f;
    out[i] = f;
    sum_sq += (f * f);
  }

  if (length <= 0) return 0.0f;
  return std::sqrt(sum_sq / static_cast<float>(length));
}

float scalar_pcm16_to_float_and_rms(const int16_t * __restrict__ pcm, float * __restrict__ out, int length, float gain) {
  const float scale = gain * (1.0f / 32768.0f);
  float sum_sq = 0.0f;
  for (int i = 0; i < length; ++i) {
    float f = static_cast<float>(pcm[i]) * scale;
    if (f < -1.0f) f = -1.0f;
    else if (f > 1.0f) f = 1.0f;
    out[i] = f;
    sum_sq += (f * f);
  }
  if (length <= 0) return 0.0f;
  return std::sqrt(sum_sq / static_cast<float>(length));
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_jarvis_edge_audio_SimdAudio_nativeProcessPcm(
    JNIEnv *env, jclass, jshortArray jPcm, jint offset, jint length,
    jfloatArray jOut, jfloat gain) {
  if (length <= 0) return 0.0f;

  jshort *pcm = env->GetShortArrayElements(jPcm, nullptr);
  jfloat *out = env->GetFloatArrayElements(jOut, nullptr);

  float rms = simd_pcm16_to_float_and_rms(
      pcm + offset, out, length, gain);

  env->ReleaseShortArrayElements(jPcm, pcm, JNI_ABORT);
  env->ReleaseFloatArrayElements(jOut, out, 0);

  return rms;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_jarvis_edge_audio_SimdAudio_nativeBenchmark(
    JNIEnv *env, jclass, jint iterations, jint frameSize) {
  std::vector<int16_t> pcm(frameSize);
  for (int i = 0; i < frameSize; ++i) {
    pcm[i] = static_cast<int16_t>((i * 12345) % 65536 - 32768);
  }
  std::vector<float> out(frameSize);

  // Warm up
  for (int w = 0; w < 100; ++w) {
    scalar_pcm16_to_float_and_rms(pcm.data(), out.data(), frameSize, 3.0f);
    simd_pcm16_to_float_and_rms(pcm.data(), out.data(), frameSize, 3.0f);
  }

  // Benchmark Scalar
  auto t0 = std::chrono::high_resolution_clock::now();
  float scalar_last = 0.0f;
  for (int it = 0; it < iterations; ++it) {
    scalar_last += scalar_pcm16_to_float_and_rms(pcm.data(), out.data(), frameSize, 3.0f);
  }
  auto t1 = std::chrono::high_resolution_clock::now();
  int64_t scalar_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(t1 - t0).count();

  // Benchmark SIMD (NEON)
  auto t2 = std::chrono::high_resolution_clock::now();
  float simd_last = 0.0f;
  for (int it = 0; it < iterations; ++it) {
    simd_last += simd_pcm16_to_float_and_rms(pcm.data(), out.data(), frameSize, 3.0f);
  }
  auto t3 = std::chrono::high_resolution_clock::now();
  int64_t simd_ns = std::chrono::duration_cast<std::chrono::nanoseconds>(t3 - t2).count();

  (void)scalar_last;
  (void)simd_last;

  jlongArray res = env->NewLongArray(2);
  jlong buf[2] = {scalar_ns, simd_ns};
  env->SetLongArrayRegion(res, 0, 2, buf);
  return res;
}
