#include <android/log.h>
#include <jni.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "ggml.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

namespace {

constexpr const char *kLogTag = "JarvisLlamaJni";

std::once_flag g_backend_once;

void ensure_backend_init() {
  std::call_once(g_backend_once, []() { llama_backend_init(); });
}

void pin_to_big_cores() {
  cpu_set_t mask;
  CPU_ZERO(&mask);
  for (int cpu = 4; cpu <= 7; ++cpu) {
    CPU_SET(cpu, &mask);
  }
  sched_setaffinity(0, sizeof(mask), &mask);
}

ggml_threadpool_t create_big_core_threadpool(int n_threads) {
  struct ggml_threadpool_params tpp = ggml_threadpool_params_default(n_threads);
  tpp.poll = 0; // Mandatory on SDM636 Android to prevent big-core barrier spin
                // livelock.
  for (int cpu = 4; cpu <= 7 && cpu < GGML_MAX_N_THREADS; ++cpu) {
    tpp.cpumask[cpu] = true;
  }
  tpp.strict_cpu = false;
  return ggml_threadpool_new(&tpp);
}

// Pins the calling thread to the Cortex-A73 big cores (4-7) and restores the
// previous affinity on destruction. mtmd creates its CLIP threadpool without a
// cpumask, so its workers inherit this thread's affinity; without pinning they
// land on A53 cores and every graph barrier waits on the slowest little core.
struct ScopedBigCoreAffinity {
  cpu_set_t previous{};
  bool restore = false;
  ScopedBigCoreAffinity() {
    if (sched_getaffinity(0, sizeof(previous), &previous) != 0)
      return;
    cpu_set_t big;
    CPU_ZERO(&big);
    for (int cpu = 4; cpu <= 7; ++cpu)
      CPU_SET(cpu, &big);
    restore = sched_setaffinity(0, sizeof(big), &big) == 0;
  }
  ~ScopedBigCoreAffinity() {
    if (restore)
      sched_setaffinity(0, sizeof(previous), &previous);
  }
};

struct LlmSession {
  llama_model *model = nullptr;
  llama_context *ctx = nullptr;
  ggml_threadpool_t threadpool = nullptr;
  std::vector<llama_token> prev_tokens;
  std::mutex mu;
  llama_sampler *cached_grammar = nullptr;
  std::string cached_grammar_str;
};

int64_t now_us() {
  return std::chrono::duration_cast<std::chrono::microseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

// Compute the longest prefix length in `buf` that ends on complete valid UTF-8
// codepoints.
size_t valid_utf8_prefix_len(const std::string &buf) {
  size_t i = 0;
  const size_t n = buf.size();
  while (i < n) {
    const unsigned char c = static_cast<unsigned char>(buf[i]);
    size_t len = 0;
    if (c < 0x80) {
      len = 1;
    } else if ((c & 0xE0) == 0xC0) {
      len = 2;
    } else if ((c & 0xF0) == 0xE0) {
      len = 3;
    } else if ((c & 0xF8) == 0xF0) {
      len = 4;
    } else {
      // Invalid leading byte; advance past it safely (replaced later if
      // emitted).
      ++i;
      continue;
    }
    if (i + len > n) {
      break; // Incomplete multi-byte sequence at tail; wait for next token.
    }
    i += len;
  }
  return i;
}

std::string sanitize_for_modified_utf8(const std::string &in) {
  std::string out;
  out.reserve(in.size());
  size_t i = 0;
  while (i < in.size()) {
    const unsigned char c = static_cast<unsigned char>(in[i]);
    if (c == 0) {
      ++i;
      continue;
    }
    if (c < 0x80) {
      out.push_back(static_cast<char>(c));
      ++i;
    } else if ((c & 0xE0) == 0xC0 && i + 1 < in.size() &&
               (static_cast<unsigned char>(in[i + 1]) & 0xC0) == 0x80) {
      out.push_back(in[i]);
      out.push_back(in[i + 1]);
      i += 2;
    } else if ((c & 0xF0) == 0xE0 && i + 2 < in.size() &&
               (static_cast<unsigned char>(in[i + 1]) & 0xC0) == 0x80 &&
               (static_cast<unsigned char>(in[i + 2]) & 0xC0) == 0x80) {
      out.push_back(in[i]);
      out.push_back(in[i + 1]);
      out.push_back(in[i + 2]);
      i += 3;
    } else if ((c & 0xF8) == 0xF0 && i + 3 < in.size() &&
               (static_cast<unsigned char>(in[i + 1]) & 0xC0) == 0x80 &&
               (static_cast<unsigned char>(in[i + 2]) & 0xC0) == 0x80 &&
               (static_cast<unsigned char>(in[i + 3]) & 0xC0) == 0x80) {
      out.push_back(in[i]);
      out.push_back(in[i + 1]);
      out.push_back(in[i + 2]);
      out.push_back(in[i + 3]);
      i += 4;
    } else {
      out.push_back('?');
      ++i;
    }
  }
  return out;
}

bool emit_piece(JNIEnv *env, jobject callback, jmethodID on_token_mid,
                std::string &pending, bool flush) {
  if (pending.empty())
    return true;
  const size_t emit_len =
      flush ? pending.size() : valid_utf8_prefix_len(pending);
  if (emit_len == 0)
    return true;

  std::string chunk = sanitize_for_modified_utf8(pending.substr(0, emit_len));
  pending.erase(0, emit_len);
  if (chunk.empty())
    return true;

  jstring j_piece = env->NewStringUTF(chunk.c_str());
  const jboolean cont = env->CallBooleanMethod(callback, on_token_mid, j_piece);
  env->DeleteLocalRef(j_piece);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  return cont == JNI_TRUE;
}

bool eval_token_range(llama_context *ctx,
                      const std::vector<llama_token> &tokens, size_t start_idx,
                      int32_t batch_cap) {
  const size_t total = tokens.size();
  if (start_idx >= total)
    return true;

  llama_batch batch = llama_batch_init(batch_cap, 0, 1);
  for (size_t offset = start_idx; offset < total;
       offset += static_cast<size_t>(batch_cap)) {
    const int32_t n = static_cast<int32_t>(
        std::min<size_t>(static_cast<size_t>(batch_cap), total - offset));
    batch.n_tokens = n;
    for (int32_t j = 0; j < n; ++j) {
      const size_t pos = offset + static_cast<size_t>(j);
      batch.token[j] = tokens[pos];
      batch.pos[j] = static_cast<llama_pos>(pos);
      batch.n_seq_id[j] = 1;
      batch.seq_id[j][0] = 0;
      batch.logits[j] = (pos == total - 1) ? 1 : 0;
    }
    if (llama_decode(ctx, batch) != 0) {
      llama_batch_free(batch);
      return false;
    }
  }
  llama_batch_free(batch);
  return true;
}

struct EmbedSession {
  llama_model *model = nullptr;
  llama_context *ctx = nullptr;
  int32_t n_embd = 0;
  std::mutex mu;
};

bool starts_with_prefix(const std::string &s, const char *prefix) {
  const size_t plen = std::strlen(prefix);
  return s.size() >= plen && s.compare(0, plen, prefix) == 0;
}

std::vector<llama_token> tokenize_text(const llama_vocab *vocab,
                                       const std::string &text,
                                       bool add_special, bool parse_special) {
  bool effective_add_special = add_special;
  if (effective_add_special &&
      (starts_with_prefix(text, "<|im_start|>") ||
       starts_with_prefix(text, "<|begin_of_text|>") ||
       starts_with_prefix(text, "<bos>"))) {
    effective_add_special = false;
  }
  const int32_t approx = static_cast<int32_t>(text.size()) + 16;
  std::vector<llama_token> tokens(approx);
  int32_t n = llama_tokenize(
      vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(),
      static_cast<int32_t>(tokens.size()), effective_add_special,
      parse_special);
  if (n < 0) {
    tokens.resize(static_cast<size_t>(-n));
    n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()),
                       tokens.data(), static_cast<int32_t>(tokens.size()),
                       effective_add_special, parse_special);
  }
  if (n < 0)
    return {};
  tokens.resize(static_cast<size_t>(n));
  return tokens;
}

struct CvecCaptureState {
  int32_t n_embd = 0;
  int32_t n_layer = 0;
  int64_t expected_tokens = 0;
  std::vector<float> *accum = nullptr;
  float sign = 1.0f;
  std::vector<float> scratch;
};

bool cvec_capture_cb(struct ggml_tensor *t, bool ask, void *user_data) {
  if (ask) {
    return t && std::strncmp(t->name, "l_out-", 6) == 0;
  }
  auto *capture = static_cast<CvecCaptureState *>(user_data);
  if (!capture || !capture->accum || !t ||
      std::strncmp(t->name, "l_out-", 6) != 0) {
    return true;
  }
  const int il = std::atoi(t->name + 6);
  if (il >= 1 && il < capture->n_layer && t->type == GGML_TYPE_F32 &&
      t->ne[0] == capture->n_embd && t->ne[1] == capture->expected_tokens &&
      capture->expected_tokens > 0) {
    const size_t embd_sz = static_cast<size_t>(capture->n_embd);
    capture->scratch.resize(embd_sz);
    const size_t offset_bytes =
        static_cast<size_t>(t->ne[1] - 1) * embd_sz * sizeof(float);
    ggml_backend_tensor_get(t, capture->scratch.data(), offset_bytes,
                            embd_sz * sizeof(float));
    float *dst = capture->accum->data() + static_cast<size_t>(il - 1) * embd_sz;
    const float s = capture->sign;
    for (int32_t d = 0; d < capture->n_embd; ++d) {
      dst[d] += s * capture->scratch[static_cast<size_t>(d)];
    }
  }
  return true;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativePinCurrentThreadToBigCores(
    JNIEnv *, jclass) {
  pin_to_big_cores();
}

JNIEXPORT jlong JNICALL Java_com_jarvis_edge_brain_LlamaJniEngine_nativeLoad(
    JNIEnv *env, jobject, jstring modelPath, jint contextTokens) {
  ensure_backend_init();
  pin_to_big_cores();

  const char *path_cstr = env->GetStringUTFChars(modelPath, nullptr);
  if (!path_cstr)
    return 0;
  std::string path_str(path_cstr);
  env->ReleaseStringUTFChars(modelPath, path_cstr);

  llama_model_params mparams = llama_model_default_params();
  mparams.n_gpu_layers = 0;
  mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

  llama_model *model = llama_model_load_from_file(path_str.c_str(), mparams);
  if (!model) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to load model: %s",
                        path_str.c_str());
    return 0;
  }

  llama_context_params cparams = llama_context_default_params();
  cparams.n_ctx = static_cast<uint32_t>(std::max<jint>(256, contextTokens));
  cparams.n_batch = 512;
  cparams.n_ubatch = 512;
  cparams.n_threads = 3;
  cparams.n_threads_batch = 3;
  cparams.no_perf = true;

  llama_context *ctx = llama_init_from_model(model, cparams);
  if (!ctx) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Failed to create context for %s", path_str.c_str());
    llama_model_free(model);
    return 0;
  }

  ggml_threadpool_t tp = create_big_core_threadpool(3);
  if (tp) {
    llama_attach_threadpool(ctx, tp, tp);
  }

  auto *session = new LlmSession();
  session->model = model;
  session->ctx = ctx;
  session->threadpool = tp;
  return reinterpret_cast<jlong>(session);
}

JNIEXPORT jlongArray JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeGenerateWithSampling(
    JNIEnv *env, jobject, jlong handle, jstring jPrompt, jstring jGrammar,
    jint maxTokens, jfloat temperature, jfloat repeatPenalty,
    jfloat freqPenalty, jfloat presencePenalty, jfloat minP, jfloat topP,
    jint penalizeUserTokens, jobject callback) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->ctx || !session->model) {
    return env->NewLongArray(5);
  }

  std::lock_guard<std::mutex> lock(session->mu);
  pin_to_big_cores();

  const char *prompt_cstr = env->GetStringUTFChars(jPrompt, nullptr);
  std::string prompt_str = prompt_cstr ? prompt_cstr : "";
  if (prompt_cstr)
    env->ReleaseStringUTFChars(jPrompt, prompt_cstr);

  std::string grammar_str;
  if (jGrammar != nullptr) {
    const char *gram_cstr = env->GetStringUTFChars(jGrammar, nullptr);
    if (gram_cstr) {
      grammar_str = gram_cstr;
      env->ReleaseStringUTFChars(jGrammar, gram_cstr);
    }
  }

  const llama_vocab *vocab = llama_model_get_vocab(session->model);
  std::vector<llama_token> prompt_tokens =
      tokenize_text(vocab, prompt_str, llama_vocab_get_add_bos(vocab), true);
  if (prompt_tokens.empty()) {
    return env->NewLongArray(5);
  }

  const uint32_t n_ctx = llama_n_ctx(session->ctx);
  if (prompt_tokens.size() >= n_ctx) {
    // Keep room for generation if an oversized prompt arrives.
    session->prev_tokens.clear();
    llama_memory_clear(llama_get_memory(session->ctx), true);
    if (prompt_tokens.size() > n_ctx - 64) {
      prompt_tokens.resize(n_ctx - 64);
    }
  }

  size_t common = 0;
  const size_t min_len =
      std::min(session->prev_tokens.size(), prompt_tokens.size());
  while (common < min_len &&
         session->prev_tokens[common] == prompt_tokens[common]) {
    ++common;
  }
  if (common == prompt_tokens.size() && common > 0) {
    // Always decode at least the last prompt token so logits exist for
    // sampling.
    --common;
  }

  llama_memory_seq_rm(llama_get_memory(session->ctx), 0,
                      static_cast<llama_pos>(common), -1);
  session->prev_tokens.resize(common);

  const int64_t t_start_us = now_us();

  const int64_t t_prefill_start_us = now_us();
  if (!eval_token_range(session->ctx, prompt_tokens, common, 512)) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Failed to evaluate prompt tokens");
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->prev_tokens.clear();
    return env->NewLongArray(5);
  }
  const int64_t t_prefill_us = now_us() - t_prefill_start_us;
  session->prev_tokens = prompt_tokens;

  int64_t t_grammar_clone_us = 0;
  llama_sampler *smpl =
      llama_sampler_chain_init(llama_sampler_chain_default_params());
  if (!grammar_str.empty()) {
    const int64_t t_g_start = now_us();
    llama_sampler *gram = nullptr;
    if (session->cached_grammar && session->cached_grammar_str == grammar_str) {
      gram = llama_sampler_clone(session->cached_grammar);
    }
    if (!gram) {
      gram = llama_sampler_init_grammar(vocab, grammar_str.c_str(), "root");
      if (gram) {
        if (session->cached_grammar) {
          llama_sampler_free(session->cached_grammar);
        }
        session->cached_grammar = llama_sampler_clone(gram);
        session->cached_grammar_str = grammar_str;
      }
    }
    t_grammar_clone_us = now_us() - t_g_start;
    if (gram) {
      llama_sampler_chain_add(smpl, gram);
    } else {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                          "Failed to parse GBNF grammar");
    }
  }
  llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
  const int32_t n_vocab = llama_vocab_n_tokens(vocab);
  llama_sampler *pen = llama_sampler_init_penalties(
      n_vocab, 64, repeatPenalty, freqPenalty, presencePenalty);
  if (pen != nullptr) {
    llama_sampler_chain_add(smpl, pen);
    if (penalizeUserTokens > 0) {
      const size_t n_pen = std::min<size_t>(
          static_cast<size_t>(penalizeUserTokens), prompt_tokens.size());
      const size_t start = prompt_tokens.size() - n_pen;
      for (size_t i = start; i < prompt_tokens.size(); ++i) {
        llama_sampler_accept(pen, prompt_tokens[i]);
      }
    }
  }
  llama_sampler_chain_add(smpl, llama_sampler_init_min_p(minP, 1));
  llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP, 1));
  llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature));
  llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

  jclass cb_cls = env->GetObjectClass(callback);
  jmethodID on_token_mid =
      env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)Z");
  env->DeleteLocalRef(cb_cls);

  int32_t out_count = 0;
  int64_t first_token_ms = -1;
  int64_t gen_start_us = 0;
  int64_t t_first_sample_us = 0;
  int64_t t_decode_us = 0;
  std::string pending_utf8;

  llama_batch one = llama_batch_init(1, 0, 1);
  const int32_t limit = std::min<int32_t>(
      maxTokens,
      std::max<int32_t>(
          0, static_cast<int32_t>(n_ctx) -
                 static_cast<int32_t>(session->prev_tokens.size()) - 1));

  for (int32_t step = 0; step < limit; ++step) {
    const int64_t t_sample_start = now_us();
    const llama_token id = llama_sampler_sample(smpl, session->ctx, -1);
    const int64_t now = now_us();
    if (step == 0) {
      t_first_sample_us = now - t_sample_start;
      first_token_ms = (now - t_start_us) / 1000;
      gen_start_us = now;
    }
    if (llama_vocab_is_eog(vocab, id)) {
      break;
    }

    char piece_buf[256];
    const int32_t n_bytes =
        llama_token_to_piece(vocab, id, piece_buf, sizeof(piece_buf), 0, false);
    if (n_bytes > 0) {
      pending_utf8.append(piece_buf, static_cast<size_t>(n_bytes));
      if (!emit_piece(env, callback, on_token_mid, pending_utf8, false)) {
        ++out_count;
        break;
      }
    }
    ++out_count;

    one.n_tokens = 1;
    one.token[0] = id;
    one.pos[0] = static_cast<llama_pos>(session->prev_tokens.size());
    one.n_seq_id[0] = 1;
    one.seq_id[0][0] = 0;
    one.logits[0] = 1;

    const int64_t t_dec_start = now_us();
    const int decode_rc = llama_decode(session->ctx, one);
    t_decode_us += (now_us() - t_dec_start);

    if (decode_rc != 0) {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                          "Failed to decode token step %d", step);
      break;
    }
    session->prev_tokens.push_back(id);
  }

  emit_piece(env, callback, on_token_mid, pending_utf8, true);

  llama_batch_free(one);
  llama_sampler_free(smpl);

  const int64_t gen_elapsed_us =
      (gen_start_us > 0) ? std::max<int64_t>(1, now_us() - gen_start_us) : 0;
  if (first_token_ms < 0) {
    first_token_ms = (now_us() - t_start_us) / 1000;
  }

  __android_log_print(
      ANDROID_LOG_INFO, "LlamaBench",
      "timing: t_prefill=%lld us (%.2f ms), t_grammar_clone=%lld us (%.2f ms), "
      "t_first_sample=%lld us (%.2f ms), t_decode=%lld us (%.2f ms), "
      "first_token_ms=%lld ms (prompt=%zu, cached=%zu, generated=%d)",
      static_cast<long long>(t_prefill_us), t_prefill_us / 1000.0,
      static_cast<long long>(t_grammar_clone_us), t_grammar_clone_us / 1000.0,
      static_cast<long long>(t_first_sample_us), t_first_sample_us / 1000.0,
      static_cast<long long>(t_decode_us), t_decode_us / 1000.0,
      static_cast<long long>(first_token_ms),
      prompt_tokens.size(), common, out_count);

  jlong stats[5] = {static_cast<jlong>(prompt_tokens.size()),
                    static_cast<jlong>(common), static_cast<jlong>(out_count),
                    static_cast<jlong>(first_token_ms),
                    static_cast<jlong>(gen_elapsed_us)};
  jlongArray out_arr = env->NewLongArray(5);
  env->SetLongArrayRegion(out_arr, 0, 5, stats);
  return out_arr;
}

JNIEXPORT jlongArray JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeGenerate(
    JNIEnv *env, jobject obj, jlong handle, jstring jPrompt, jstring jGrammar,
    jint maxTokens, jobject callback) {
  return Java_com_jarvis_edge_brain_LlamaJniEngine_nativeGenerateWithSampling(
      env, obj, handle, jPrompt, jGrammar, maxTokens, 0.45f, 1.18f, 0.15f,
      0.10f, 0.05f, 0.90f, 0, callback);
}

JNIEXPORT jlong JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeLoadLora(
    JNIEnv *env, jobject, jlong handle, jstring jLoraPath) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->model || jLoraPath == nullptr) {
    return 0;
  }
  const char *path_cstr = env->GetStringUTFChars(jLoraPath, nullptr);
  if (!path_cstr) {
    return 0;
  }
  std::string path_str(path_cstr);
  env->ReleaseStringUTFChars(jLoraPath, path_cstr);

  std::lock_guard<std::mutex> lock(session->mu);
  llama_adapter_lora *adapter =
      llama_adapter_lora_init(session->model, path_str.c_str());
  if (!adapter) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Failed to load LoRA adapter: %s", path_str.c_str());
    return 0;
  }
  return reinterpret_cast<jlong>(adapter);
}

JNIEXPORT jint JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeSetLoraAdapters(
    JNIEnv *env, jobject, jlong handle, jlongArray jLoraHandles,
    jfloatArray jScales) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->ctx) {
    return -1;
  }
  std::lock_guard<std::mutex> lock(session->mu);

  std::vector<llama_adapter_lora *> adapters;
  std::vector<float> scales;
  if (jLoraHandles != nullptr) {
    const jsize n = env->GetArrayLength(jLoraHandles);
    if (n > 0) {
      std::vector<jlong> raw_handles(static_cast<size_t>(n));
      env->GetLongArrayRegion(jLoraHandles, 0, n, raw_handles.data());
      std::vector<jfloat> raw_scales(static_cast<size_t>(n), 1.0f);
      if (jScales != nullptr) {
        const jsize ns = std::min(n, env->GetArrayLength(jScales));
        if (ns > 0) {
          env->GetFloatArrayRegion(jScales, 0, ns, raw_scales.data());
        }
      }
      for (jsize i = 0; i < n; ++i) {
        if (raw_handles[i] != 0) {
          adapters.push_back(
              reinterpret_cast<llama_adapter_lora *>(raw_handles[i]));
          scales.push_back(raw_scales[i]);
        }
      }
    }
  }

  const int32_t rc = llama_set_adapters_lora(
      session->ctx, adapters.empty() ? nullptr : adapters.data(),
      adapters.size(), scales.empty() ? nullptr : scales.data());
  llama_memory_clear(llama_get_memory(session->ctx), true);
  session->prev_tokens.clear();
  return static_cast<jint>(rc);
}

JNIEXPORT void JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeFreeLora(JNIEnv *, jobject,
                                                         jlong loraHandle) {
  auto *adapter = reinterpret_cast<llama_adapter_lora *>(loraHandle);
  if (adapter != nullptr) {
    llama_adapter_lora_free(adapter);
  }
}

JNIEXPORT jint JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeApplyControlVector(
    JNIEnv *env, jobject, jlong handle, jfloatArray jData, jint nEmbd,
    jint ilStart, jint ilEnd) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->ctx) {
    return -1;
  }
  std::lock_guard<std::mutex> lock(session->mu);

  int32_t rc = 0;
  if (jData == nullptr) {
    rc = llama_set_adapter_cvec(session->ctx, nullptr, 0, 0, 0, 0);
  } else {
    const jsize len = env->GetArrayLength(jData);
    std::vector<float> vec(static_cast<size_t>(len));
    if (len > 0) {
      env->GetFloatArrayRegion(jData, 0, len, vec.data());
    }
    int32_t embd = static_cast<int32_t>(nEmbd);
    if (embd <= 0 && session->model != nullptr) {
      embd = llama_model_n_embd(session->model);
    }
    rc = llama_set_adapter_cvec(
        session->ctx, vec.empty() ? nullptr : vec.data(), vec.size(), embd,
        static_cast<int32_t>(ilStart), static_cast<int32_t>(ilEnd));
  }

  llama_memory_clear(llama_get_memory(session->ctx), true);
  session->prev_tokens.clear();
  return static_cast<jint>(rc);
}

JNIEXPORT jintArray JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeModelInfo(JNIEnv *env, jobject,
                                                          jlong handle) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  jint info[2] = {0, 0};
  if (session && session->model) {
    std::lock_guard<std::mutex> lock(session->mu);
    if (session->model) {
      info[0] = static_cast<jint>(llama_model_n_embd(session->model));
      info[1] = static_cast<jint>(llama_model_n_layer(session->model));
    }
  }
  jintArray out = env->NewIntArray(2);
  if (out) {
    env->SetIntArrayRegion(out, 0, 2, info);
  }
  return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeTrainControlVector(
    JNIEnv *env, jobject, jlong handle, jobjectArray jPosPrompts,
    jobjectArray jNegPrompts, jint ilStart, jint ilEnd) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->model || !jPosPrompts || !jNegPrompts) {
    return env->NewFloatArray(0);
  }

  std::lock_guard<std::mutex> lock(session->mu);
  pin_to_big_cores();

  const int32_t n_embd = llama_model_n_embd(session->model);
  const int32_t n_layer = llama_model_n_layer(session->model);
  if (n_embd <= 0 || n_layer <= 1) {
    return env->NewFloatArray(0);
  }

  const jsize n_pos = env->GetArrayLength(jPosPrompts);
  const jsize n_neg = env->GetArrayLength(jNegPrompts);
  const jsize n_pairs = std::min(n_pos, n_neg);
  if (n_pairs <= 0) {
    return env->NewFloatArray(0);
  }

  const int32_t clamped_start =
      std::max<int32_t>(1, static_cast<int32_t>(ilStart));
  const int32_t clamped_end =
      std::min<int32_t>(n_layer - 1, static_cast<int32_t>(ilEnd));
  if (clamped_start > clamped_end) {
    return env->NewFloatArray(0);
  }

  std::vector<std::string> pos_prompts;
  std::vector<std::string> neg_prompts;
  pos_prompts.reserve(static_cast<size_t>(n_pairs));
  neg_prompts.reserve(static_cast<size_t>(n_pairs));
  for (jsize i = 0; i < n_pairs; ++i) {
    auto j_pos =
        static_cast<jstring>(env->GetObjectArrayElement(jPosPrompts, i));
    auto j_neg =
        static_cast<jstring>(env->GetObjectArrayElement(jNegPrompts, i));
    std::string p_str;
    std::string n_str;
    if (j_pos) {
      const char *c = env->GetStringUTFChars(j_pos, nullptr);
      if (c) {
        p_str = c;
        env->ReleaseStringUTFChars(j_pos, c);
      }
      env->DeleteLocalRef(j_pos);
    }
    if (j_neg) {
      const char *c = env->GetStringUTFChars(j_neg, nullptr);
      if (c) {
        n_str = c;
        env->ReleaseStringUTFChars(j_neg, c);
      }
      env->DeleteLocalRef(j_neg);
    }
    if (!p_str.empty() && !n_str.empty()) {
      pos_prompts.push_back(std::move(p_str));
      neg_prompts.push_back(std::move(n_str));
    }
  }
  if (pos_prompts.empty()) {
    return env->NewFloatArray(0);
  }

  const size_t embd_sz = static_cast<size_t>(n_embd);
  const size_t total_elems = static_cast<size_t>(n_layer - 1) * embd_sz;
  std::vector<float> sum_diff(total_elems, 0.0f);
  std::vector<float> pair_diff(total_elems, 0.0f);

  CvecCaptureState capture;
  capture.n_embd = n_embd;
  capture.n_layer = n_layer;
  capture.accum = &pair_diff;

  llama_context_params cparams = llama_context_default_params();
  cparams.n_ctx = 256;
  cparams.n_batch = 256;
  cparams.n_ubatch = 256;
  cparams.n_threads = 3;
  cparams.n_threads_batch = 3;
  cparams.no_perf = true;
  cparams.cb_eval = cvec_capture_cb;
  cparams.cb_eval_user_data = &capture;

  llama_context *tmp_ctx = llama_init_from_model(session->model, cparams);
  if (!tmp_ctx) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Failed to create temporary context for cvec training");
    return env->NewFloatArray(0);
  }
  if (session->threadpool) {
    llama_attach_threadpool(tmp_ctx, session->threadpool, session->threadpool);
  }

  const llama_vocab *vocab = llama_model_get_vocab(session->model);
  const bool add_bos = llama_vocab_get_add_bos(vocab);
  int32_t valid_pairs = 0;

  for (size_t i = 0; i < pos_prompts.size(); ++i) {
    std::vector<llama_token> pos_tokens =
        tokenize_text(vocab, pos_prompts[i], add_bos, true);
    std::vector<llama_token> neg_tokens =
        tokenize_text(vocab, neg_prompts[i], add_bos, true);
    if (pos_tokens.empty() || neg_tokens.empty()) {
      continue;
    }
    if (pos_tokens.size() > 256) {
      pos_tokens.resize(256);
    }
    if (neg_tokens.size() > 256) {
      neg_tokens.resize(256);
    }

    std::fill(pair_diff.begin(), pair_diff.end(), 0.0f);

    llama_memory_clear(llama_get_memory(tmp_ctx), true);
    capture.sign = 1.0f;
    capture.expected_tokens = static_cast<int64_t>(pos_tokens.size());
    if (!eval_token_range(tmp_ctx, pos_tokens, 0, 256)) {
      continue;
    }

    llama_memory_clear(llama_get_memory(tmp_ctx), true);
    capture.sign = -1.0f;
    capture.expected_tokens = static_cast<int64_t>(neg_tokens.size());
    if (!eval_token_range(tmp_ctx, neg_tokens, 0, 256)) {
      continue;
    }

    for (size_t k = 0; k < total_elems; ++k) {
      sum_diff[k] += pair_diff[k];
    }
    ++valid_pairs;
  }

  if (session->threadpool) {
    llama_detach_threadpool(tmp_ctx);
  }
  llama_free(tmp_ctx);

  if (valid_pairs <= 0) {
    return env->NewFloatArray(0);
  }

  const float inv_pairs = 1.0f / static_cast<float>(valid_pairs);
  for (int32_t il = 1; il < n_layer; ++il) {
    float *layer_ptr = sum_diff.data() + static_cast<size_t>(il - 1) * embd_sz;
    if (il < clamped_start || il > clamped_end) {
      std::fill(layer_ptr, layer_ptr + embd_sz, 0.0f);
      continue;
    }
    double norm_sq = 0.0;
    for (int32_t d = 0; d < n_embd; ++d) {
      const float val = layer_ptr[d] * inv_pairs;
      layer_ptr[d] = val;
      norm_sq += static_cast<double>(val) * static_cast<double>(val);
    }
    if (norm_sq > 1e-12) {
      const float inv_norm = static_cast<float>(1.0 / std::sqrt(norm_sq));
      for (int32_t d = 0; d < n_embd; ++d) {
        layer_ptr[d] *= inv_norm;
      }
    }
  }

  jfloatArray out_arr = env->NewFloatArray(static_cast<jsize>(total_elems));
  if (out_arr) {
    env->SetFloatArrayRegion(out_arr, 0, static_cast<jsize>(total_elems),
                             sum_diff.data());
  }
  return out_arr;
}

JNIEXPORT void JNICALL Java_com_jarvis_edge_brain_LlamaJniEngine_nativeUnload(
    JNIEnv *, jobject, jlong handle) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session)
    return;
  {
    std::lock_guard<std::mutex> lock(session->mu);
    if (session->cached_grammar) {
      llama_sampler_free(session->cached_grammar);
      session->cached_grammar = nullptr;
    }
    if (session->ctx) {
      llama_detach_threadpool(session->ctx);
      llama_free(session->ctx);
      session->ctx = nullptr;
    }
    if (session->model) {
      llama_model_free(session->model);
      session->model = nullptr;
    }
    if (session->threadpool) {
      ggml_threadpool_free(session->threadpool);
      session->threadpool = nullptr;
    }
  }
  delete session;
}

JNIEXPORT void JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeDescribeImage(
    JNIEnv *env, jclass, jstring jModelPath, jstring jMmprojPath,
    jbyteArray jRgbBytes, jint width, jint height, jstring jQuestion,
    jint maxTokens, jobject callback) {
  ensure_backend_init();
  pin_to_big_cores();

  const char *m_cstr = env->GetStringUTFChars(jModelPath, nullptr);
  const char *p_cstr = env->GetStringUTFChars(jMmprojPath, nullptr);
  const char *q_cstr = env->GetStringUTFChars(jQuestion, nullptr);
  std::string model_path = m_cstr ? m_cstr : "";
  std::string mmproj_path = p_cstr ? p_cstr : "";
  std::string question = q_cstr ? q_cstr : "Describe this image concisely.";
  if (m_cstr)
    env->ReleaseStringUTFChars(jModelPath, m_cstr);
  if (p_cstr)
    env->ReleaseStringUTFChars(jMmprojPath, p_cstr);
  if (q_cstr)
    env->ReleaseStringUTFChars(jQuestion, q_cstr);

  const jsize rgb_len = env->GetArrayLength(jRgbBytes);
  std::vector<unsigned char> rgb(static_cast<size_t>(rgb_len));
  env->GetByteArrayRegion(jRgbBytes, 0, rgb_len,
                          reinterpret_cast<jbyte *>(rgb.data()));

  const auto vlm_t0 = std::chrono::steady_clock::now();
  ScopedBigCoreAffinity big_core_affinity;
  auto vlm_ms = [&vlm_t0]() {
    return static_cast<long long>(
        std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - vlm_t0)
            .count());
  };

  llama_model_params mparams = llama_model_default_params();
  mparams.n_gpu_layers = 0;
  mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
  llama_model *vlm_model =
      llama_model_load_from_file(model_path.c_str(), mparams);
  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "VLM timing: model loaded at %lld ms", vlm_ms());
  if (!vlm_model) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "VLM failed to load model %s", model_path.c_str());
    return;
  }

  llama_context_params cparams = llama_context_default_params();
  cparams.n_ctx = 2048;
  cparams.n_batch = 256;
  cparams.n_ubatch = 256;
  cparams.n_threads = 4;
  cparams.n_threads_batch = 4;
  cparams.no_perf = true;
  llama_context *vlm_ctx = llama_init_from_model(vlm_model, cparams);
  ggml_threadpool_t tp = create_big_core_threadpool(4);
  if (vlm_ctx && tp) {
    llama_attach_threadpool(vlm_ctx, tp, tp);
  }

  struct mtmd_context_params vparams = mtmd_context_params_default();
  vparams.use_gpu = false;
  vparams.n_threads = 4;
  vparams.warmup = false;
  vparams.image_min_tokens = 64;
  vparams.image_max_tokens = 64;
  mtmd_context *mctx =
      vlm_ctx ? mtmd_init_from_file(mmproj_path.c_str(), vlm_model, vparams)
              : nullptr;
  __android_log_print(ANDROID_LOG_INFO, kLogTag,
                      "VLM timing: ctx+mmproj ready at %lld ms", vlm_ms());

  if (mctx && vlm_ctx) {
    mtmd_bitmap *bmp =
        mtmd_bitmap_init(static_cast<uint32_t>(width),
                         static_cast<uint32_t>(height), rgb.data());
    std::string prompt = std::string("<|im_start|>User:") +
                         mtmd_default_marker() + question +
                         "<end_of_utterance>\nAssistant:";
    struct mtmd_input_text inp = {prompt.c_str(), prompt.size(), true, true};
    const mtmd_bitmap *bitmaps[1] = {bmp};
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();

    if (bmp && mtmd_tokenize(mctx, chunks, &inp, bitmaps, 1) == 0) {
      llama_pos n_past = 0;
      const int eval_rc = mtmd_helper_eval_chunks(mctx, vlm_ctx, chunks, n_past,
                                                  0, 256, true, &n_past);
      __android_log_print(ANDROID_LOG_INFO, kLogTag,
                          "VLM timing: image encode + prefill done at %lld ms "
                          "(n_past=%d, rc=%d)",
                          vlm_ms(), static_cast<int>(n_past), eval_rc);
      if (eval_rc == 0) {
        const llama_vocab *vocab = llama_model_get_vocab(vlm_model);
        llama_sampler *smpl =
            llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
        llama_sampler_chain_add(smpl,
                                llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

        jclass cb_cls = env->GetObjectClass(callback);
        jmethodID on_token_mid =
            env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)Z");
        env->DeleteLocalRef(cb_cls);

        llama_batch one = llama_batch_init(1, 0, 1);
        std::string pending;
        for (int32_t step = 0; step < maxTokens; ++step) {
          const llama_token id = llama_sampler_sample(smpl, vlm_ctx, -1);
          if (llama_vocab_is_eog(vocab, id))
            break;

          char buf[256];
          const int32_t nb =
              llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
          if (nb > 0) {
            pending.append(buf, static_cast<size_t>(nb));
            if (!emit_piece(env, callback, on_token_mid, pending, false))
              break;
          }
          one.n_tokens = 1;
          one.token[0] = id;
          one.pos[0] = n_past++;
          one.n_seq_id[0] = 1;
          one.seq_id[0][0] = 0;
          one.logits[0] = 1;
          if (llama_decode(vlm_ctx, one) != 0)
            break;
        }
        emit_piece(env, callback, on_token_mid, pending, true);
        __android_log_print(ANDROID_LOG_INFO, kLogTag,
                            "VLM timing: generation done at %lld ms", vlm_ms());
        llama_batch_free(one);
        llama_sampler_free(smpl);
      }
    }
    mtmd_input_chunks_free(chunks);
    if (bmp)
      mtmd_bitmap_free(bmp);
  }

  if (mctx)
    mtmd_free(mctx);
  if (vlm_ctx) {
    llama_detach_threadpool(vlm_ctx);
    llama_free(vlm_ctx);
  }
  llama_model_free(vlm_model);
  if (tp)
    ggml_threadpool_free(tp);
}

JNIEXPORT jlong JNICALL
Java_com_jarvis_edge_brain_LlamaEmbeddingEngine_nativeLoadEmbedder(
    JNIEnv *env, jobject, jstring jModelPath) {
  ensure_backend_init();

  const char *path_cstr = env->GetStringUTFChars(jModelPath, nullptr);
  if (!path_cstr)
    return 0;
  std::string path_str(path_cstr);
  env->ReleaseStringUTFChars(jModelPath, path_cstr);

  llama_model_params mparams = llama_model_default_params();
  mparams.n_gpu_layers = 0;
  mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

  llama_model *model = llama_model_load_from_file(path_str.c_str(), mparams);
  if (!model) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Failed to load embedder model: %s", path_str.c_str());
    return 0;
  }

  llama_context_params cparams = llama_context_default_params();
  cparams.embeddings = true;
  cparams.pooling_type = LLAMA_POOLING_TYPE_MEAN;
  cparams.n_ctx = 512;
  cparams.n_batch = 512;
  cparams.n_ubatch = 512;
  cparams.n_threads = 2;
  cparams.n_threads_batch = 2;
  cparams.no_perf = true;

  llama_context *ctx = llama_init_from_model(model, cparams);
  if (!ctx) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Failed to create embedder context for %s",
                        path_str.c_str());
    llama_model_free(model);
    return 0;
  }

  auto *session = new EmbedSession();
  session->model = model;
  session->ctx = ctx;
  session->n_embd = llama_model_n_embd(model);
  return reinterpret_cast<jlong>(session);
}

JNIEXPORT jfloatArray JNICALL
Java_com_jarvis_edge_brain_LlamaEmbeddingEngine_nativeEmbed(
    JNIEnv *env, jobject, jlong embedHandle, jstring jText) {
  auto *session = reinterpret_cast<EmbedSession *>(embedHandle);
  if (!session || !session->ctx || !session->model || session->n_embd <= 0 ||
      jText == nullptr) {
    return env->NewFloatArray(0);
  }

  std::lock_guard<std::mutex> lock(session->mu);

  const char *text_cstr = env->GetStringUTFChars(jText, nullptr);
  std::string text = text_cstr ? text_cstr : "";
  if (text_cstr)
    env->ReleaseStringUTFChars(jText, text_cstr);

  const llama_vocab *vocab = llama_model_get_vocab(session->model);
  std::vector<llama_token> tokens =
      tokenize_text(vocab, text, llama_vocab_get_add_bos(vocab), false);
  if (tokens.empty()) {
    return env->NewFloatArray(0);
  }
  if (tokens.size() > 512) {
    tokens.resize(512);
  }

  llama_memory_clear(llama_get_memory(session->ctx), true);

  llama_batch batch =
      llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
  batch.n_tokens = static_cast<int32_t>(tokens.size());
  for (size_t i = 0; i < tokens.size(); ++i) {
    batch.token[i] = tokens[i];
    batch.pos[i] = static_cast<llama_pos>(i);
    batch.n_seq_id[i] = 1;
    batch.seq_id[i][0] = 0;
    batch.logits[i] = 1;
  }

  const int decode_rc = llama_decode(session->ctx, batch);
  llama_batch_free(batch);
  if (decode_rc != 0) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Embedder llama_decode failed: %d", decode_rc);
    return env->NewFloatArray(0);
  }

  const float *emb = nullptr;
  if (llama_pooling_type(session->ctx) != LLAMA_POOLING_TYPE_NONE) {
    emb = llama_get_embeddings_seq(session->ctx, 0);
  }
  if (!emb) {
    emb = llama_get_embeddings_ith(session->ctx, -1);
  }
  if (!emb) {
    return env->NewFloatArray(0);
  }

  std::vector<float> out(static_cast<size_t>(session->n_embd));
  double norm_sq = 0.0;
  for (int32_t i = 0; i < session->n_embd; ++i) {
    out[i] = emb[i];
    norm_sq += static_cast<double>(emb[i]) * static_cast<double>(emb[i]);
  }
  if (norm_sq > 1e-12) {
    const float inv_norm = static_cast<float>(1.0 / std::sqrt(norm_sq));
    for (int32_t i = 0; i < session->n_embd; ++i) {
      out[i] *= inv_norm;
    }
  }

  jfloatArray out_arr = env->NewFloatArray(session->n_embd);
  env->SetFloatArrayRegion(out_arr, 0, session->n_embd, out.data());
  return out_arr;
}

JNIEXPORT void JNICALL
Java_com_jarvis_edge_brain_LlamaEmbeddingEngine_nativeUnloadEmbedder(
    JNIEnv *, jobject, jlong embedHandle) {
  auto *session = reinterpret_cast<EmbedSession *>(embedHandle);
  if (!session)
    return;
  {
    std::lock_guard<std::mutex> lock(session->mu);
    if (session->ctx) {
      llama_free(session->ctx);
      session->ctx = nullptr;
    }
    if (session->model) {
      llama_model_free(session->model);
      session->model = nullptr;
    }
  }
  delete session;
}

} // extern "C"
