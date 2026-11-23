#include <android/log.h>
#include <dlfcn.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <jni.h>
#include <fcntl.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <string>
#include <tuple>
#include <vector>

#include "ggml-backend.h"
#include "ggml-cpu.h"
#include "ggml.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"

#include "cpu_topology.h"

namespace {

constexpr const char *kLogTag = "JarvisLlamaJni";

std::once_flag g_backend_once;

// The CPU code is built several times, from plain ARMv8.0 NEON up to dot-product / fp16 / int8-matmul variants
// (libggml-cpu-android_*.so). ggml asks each one whether this processor supports it and loads the best. Setting the
// system property debug.jarvis.cpu_variant (e.g. android_armv8.0_1) forces one, for A/B timing.
std::string g_cpu_features = "unknown";

std::string native_lib_dir() {
  Dl_info info{};
  if (dladdr(reinterpret_cast<void *>(&native_lib_dir), &info) == 0 || !info.dli_fname) return "";
  std::string p(info.dli_fname);
  size_t slash = p.rfind('/');
  return slash == std::string::npos ? "" : p.substr(0, slash);
}

void ensure_backend_init() {
  std::call_once(g_backend_once, []() {
    llama_backend_init();
    const std::string dir = native_lib_dir();
    char forced[PROP_VALUE_MAX] = {0};
    __system_property_get("debug.jarvis.cpu_variant", forced);
    if (forced[0] != 0 && !dir.empty()) {
      std::string path = dir + "/libggml-cpu-" + forced + ".so";
      if (!ggml_backend_load(path.c_str())) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "forced CPU variant %s failed to load", forced);
      }
    }
    if (ggml_backend_reg_by_name("CPU") == nullptr) {
      ggml_backend_load_all_from_path(dir.empty() ? nullptr : dir.c_str());
    }
    ggml_backend_reg_t cpu = ggml_backend_reg_by_name("CPU");
    if (cpu) {
      std::string feats;
      auto get_features = reinterpret_cast<ggml_backend_get_features_t>(
          ggml_backend_reg_get_proc_address(cpu, "ggml_backend_get_features"));
      if (get_features) {
        for (ggml_backend_feature *f = get_features(cpu); f && f->name; ++f) {
          feats += (feats.empty() ? "" : " ") + std::string(f->name) + "=" + f->value;
        }
      }
      g_cpu_features = feats.empty() ? "baseline" : feats;
    } else {
      g_cpu_features = "none";
      __android_log_print(ANDROID_LOG_ERROR, kLogTag, "no CPU backend could be loaded from %s", dir.c_str());
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "CPU backend: %s%s%s | %s", g_cpu_features.c_str(),
                        forced[0] ? " (forced " : "", forced[0] ? (std::string(forced) + ")").c_str() : "",
                        cputopo::describe().c_str());
  });
}

void pin_to_big_cores() {
  cpu_set_t mask = cputopo::top_cores_mask(cputopo::llm_cores());
  sched_setaffinity(0, sizeof(mask), &mask);
}

// ggml_threadpool_new/free live in the CPU backend, which is now loaded at run time, so they are looked up.
ggml_threadpool_t threadpool_new(ggml_threadpool_params *p) {
  using fn_t = ggml_threadpool_t (*)(ggml_threadpool_params *);
  static fn_t fn = nullptr;
  if (!fn) {
    ggml_backend_reg_t cpu = ggml_backend_reg_by_name("CPU");
    if (cpu) fn = reinterpret_cast<fn_t>(ggml_backend_reg_get_proc_address(cpu, "ggml_threadpool_new"));
  }
  return fn ? fn(p) : nullptr;
}

void threadpool_free(ggml_threadpool_t tp) {
  using fn_t = void (*)(ggml_threadpool_t);
  static fn_t fn = nullptr;
  if (!tp) return;
  if (!fn) {
    ggml_backend_reg_t cpu = ggml_backend_reg_by_name("CPU");
    if (cpu) fn = reinterpret_cast<fn_t>(ggml_backend_reg_get_proc_address(cpu, "ggml_threadpool_free"));
  }
  if (fn) fn(tp);
}

// Samples like llama.cpp's common sampler: the chain (top-k first) runs on the raw logits and only the chosen token
// is checked against the grammar; the grammar runs over the whole vocabulary only when that token is rejected.
// Gemma's 262k vocabulary made a grammar-first chain cost ~100+ ms per token.
llama_token sample_lazy_grammar(llama_sampler *chain, llama_sampler *grammar, llama_context *ctx, int32_t n_vocab,
                                std::vector<llama_token_data> &cand) {
  const float *logits = llama_get_logits_ith(ctx, -1);
  auto fill = [&]() {
    cand.resize(static_cast<size_t>(n_vocab));
    for (int32_t i = 0; i < n_vocab; ++i) cand[i] = llama_token_data{i, logits[i], 0.0f};
    return llama_token_data_array{cand.data(), cand.size(), -1, false};
  };
  llama_token_data_array arr = fill();
  llama_sampler_apply(chain, &arr);
  llama_token id = arr.data[arr.selected].id;
  if (grammar) {
    llama_token_data single{id, 1.0f, 0.0f};
    llama_token_data_array one{&single, 1, -1, false};
    llama_sampler_apply(grammar, &one);
    if (std::isinf(single.logit)) {
      arr = fill();
      llama_sampler_apply(grammar, &arr);
      llama_sampler_apply(chain, &arr);
      id = arr.data[arr.selected].id;
    }
    llama_sampler_accept(grammar, id);
  }
  llama_sampler_accept(chain, id);
  return id;
}

// n_threads workers that may run on the n_cores fastest cores (n_cores defaults to the tuned chat-model set).
ggml_threadpool_t create_big_core_threadpool(int n_threads, int n_cores = 0) {
  struct ggml_threadpool_params tpp = ggml_threadpool_params_default(n_threads);
  tpp.poll = 0; // Mandatory on SDM636 Android to prevent big-core barrier spin
                // livelock.
  if (n_cores <= 0) n_cores = std::max(n_threads, cputopo::llm_cores());
  const cpu_set_t mask = cputopo::top_cores_mask(n_cores);
  for (int cpu = 0; cpu < GGML_MAX_N_THREADS && cpu < CPU_SETSIZE; ++cpu) {
    if (CPU_ISSET(cpu, &mask)) tpp.cpumask[cpu] = true;
  }
  tpp.strict_cpu = false;
  return threadpool_new(&tpp);
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
    cpu_set_t big = cputopo::top_cores_mask(cputopo::llm_cores());
    restore = sched_setaffinity(0, sizeof(big), &big) == 0;
  }
  ~ScopedBigCoreAffinity() {
    if (restore)
      sched_setaffinity(0, sizeof(previous), &previous);
  }
};

// ---- Thread tuning -------------------------------------------------------------------------------------------
// The fastest thread count depends on the phone: on a 4+4 big.LITTLE chip 3-4 big cores win, on a 2+6 chip adding
// little cores may help. Kotlin (ThreadTuner) times a few counts with nativeBenchThreads while the assistant is idle,
// picks the winners and applies them with nativeSetThreads; until then the defaults below are used.
std::atomic<bool> g_bench_cancel{false};

struct TuneDeadline {
  std::chrono::steady_clock::time_point until;
  bool timed_out = false;
};

// Cancels a timing run when it passes its deadline (spreading threads over slow cores can make ggml's barriers crawl,
// seen on the Snapdragon 636 with 8 threads) or when the user needs the model (nativeCancelBench).
bool tune_abort_cb(void *data) {
  auto *d = static_cast<TuneDeadline *>(data);
  if (std::chrono::steady_clock::now() > d->until) d->timed_out = true;
  return d->timed_out || g_bench_cancel.load();
}

double ms_since(std::chrono::steady_clock::time_point t0) {
  return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

// Mirrors ThreadTuner.defaultChoice in Kotlin.
void default_threads(int &decode, int &batch) {
  const cputopo::Topology &t = cputopo::get();
  if (t.top_class >= 4) {
    decode = 3;  // one big core left for audio and speech synthesis
    batch = 4;
  } else if (t.top_class == 3) {
    decode = batch = 3;
  } else {
    decode = batch = std::min(4, t.ncpu);
  }
}

struct LlmSession {
  llama_model *model = nullptr;
  llama_context *ctx = nullptr;
  ggml_threadpool_t threadpool = nullptr;       // decode: 3 big cores, one left for audio/TTS
  ggml_threadpool_t threadpool_batch = nullptr; // prompt prefill: all 4 big cores
  std::vector<llama_token> prev_tokens;
  std::mutex mu;
  llama_sampler *cached_grammar = nullptr;
  std::string cached_grammar_str;
  // Raw-model log-probability of the token most recently emitted to the callback (NaN before the first). Read from
  // inside the token callback without taking mu, which generate already holds.
  std::atomic<float> last_logp{NAN};
  // Every threadpool this session has made, keyed by (threads, cores). The CPU backend keeps a pointer to the last
  // pool it ran and pauses it when the next one is attached, so a pool may only be freed after the context is gone:
  // switching thread counts reuses these instead of freeing (freeing early left ggml pausing freed memory and hung).
  std::vector<std::tuple<int, int, ggml_threadpool_t>> pools;
};

// The session's pool for n threads on the current chat-model core set, made on first use.
ggml_threadpool_t session_pool(LlmSession *s, int n) {
  const int cores = std::max(n, cputopo::llm_cores());
  for (auto &p : s->pools) {
    if (std::get<0>(p) == n && std::get<1>(p) == cores) return std::get<2>(p);
  }
  ggml_threadpool_t tp = create_big_core_threadpool(n, cores);
  if (tp) s->pools.emplace_back(n, cores, tp);
  return tp;
}

void free_session_pools(LlmSession *s) {
  for (auto &p : s->pools) threadpool_free(std::get<2>(p));
  s->pools.clear();
  s->threadpool = nullptr;
  s->threadpool_batch = nullptr;
}

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
  for (size_t offset = start_idx; offset < total;) {
    int32_t n = static_cast<int32_t>(
        std::min<size_t>(static_cast<size_t>(batch_cap), total - offset));
    // ggml's 2x2 Q4_0 kernel (ARMv8.0) only runs for an even token count: send an odd batch as even + 1.
    if (n > 1 && (n % 2) != 0) --n;
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
    offset += static_cast<size_t>(n);
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
  // 128 reads prompts as fast as 512 on the Pad (28.8 tok/s either way) but the compute buffer drops from 314 MB to
  // 78 MB with Qwen2.5 1.5B, whose 152k-word vocabulary makes every batch row cost ~0.6 MB of logits.
  cparams.n_ubatch = 128;
  // Placeholder counts; the tuned ones are applied with the threadpools below.
  cparams.n_threads = 3;
  cparams.n_threads_batch = 4;
  cparams.no_perf = true;
  // Auto would turn flash attention on; on the A73 cores it is slower (tg32 @ 384 ctx: 7.3 -> 6.0 t/s).
  cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

  llama_context *ctx = llama_init_from_model(model, cparams);
  if (!ctx) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "Failed to create context for %s", path_str.c_str());
    llama_model_free(model);
    return 0;
  }

  int n_decode = 3, n_batch = 4;
  default_threads(n_decode, n_batch);
  cputopo::set_llm_cores(std::max(n_decode, n_batch));
  pin_to_big_cores();
  llama_set_n_threads(ctx, n_decode, n_batch);

  auto *session = new LlmSession();
  session->model = model;
  session->ctx = ctx;
  // Workers may float over the whole chat-model core set (as with 3 threads on 4 big cores before).
  ggml_threadpool_t tp = session_pool(session, n_decode);
  ggml_threadpool_t tp_batch = tp ? session_pool(session, n_batch) : nullptr;
  if (tp) {
    llama_attach_threadpool(ctx, tp, tp_batch ? tp_batch : tp);
  }
  session->threadpool = tp;
  session->threadpool_batch = tp_batch;
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
  session->last_logp.store(NAN);

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
  llama_sampler *grammar = nullptr;
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
      grammar = gram;  // checked lazily per token below, not over the whole vocabulary
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

  std::vector<llama_token_data> cand;
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
    const llama_token id = sample_lazy_grammar(smpl, grammar, session->ctx, n_vocab, cand);
    const int64_t now = now_us();
    if (step == 0) {
      t_first_sample_us = now - t_sample_start;
      first_token_ms = (now - t_start_us) / 1000;
      gen_start_us = now;
    }
    if (llama_vocab_is_eog(vocab, id)) {
      break;
    }

    // Confidence: probability the unconstrained model gave this token (softmax over the raw logits).
    if (const float *lg = llama_get_logits_ith(session->ctx, -1)) {
      float mx = lg[0];
      for (int32_t i = 1; i < n_vocab; ++i) mx = std::max(mx, lg[i]);
      float sum = 0.0f;
      for (int32_t i = 0; i < n_vocab; ++i) sum += std::exp(lg[i] - mx);
      session->last_logp.store(lg[id] - mx - std::log(sum));
    } else {
      session->last_logp.store(NAN);
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
  if (grammar) llama_sampler_free(grammar);

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

JNIEXPORT jboolean JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeSaveState(JNIEnv *env, jobject,
                                                          jlong handle,
                                                          jstring path) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->ctx || !path)
    return JNI_FALSE;
  const char *c_path = env->GetStringUTFChars(path, nullptr);
  if (!c_path)
    return JNI_FALSE;
  const std::string final_path(c_path);
  env->ReleaseStringUTFChars(path, c_path);
  const std::string tmp_path = final_path + ".tmp";

  std::lock_guard<std::mutex> lock(session->mu);
  if (session->prev_tokens.empty())
    return JNI_FALSE;
  const size_t written = llama_state_seq_save_file(
      session->ctx, tmp_path.c_str(), 0, session->prev_tokens.data(),
      session->prev_tokens.size());
  if (written == 0) {
    std::remove(tmp_path.c_str());
    return JNI_FALSE;
  }
  const int fd = open(tmp_path.c_str(), O_RDONLY);
  if (fd < 0) {
    std::remove(tmp_path.c_str());
    return JNI_FALSE;
  }
  const bool synced = fsync(fd) == 0;
  close(fd);
  if (!synced || std::rename(tmp_path.c_str(), final_path.c_str()) != 0) {
    std::remove(tmp_path.c_str());
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeLoadState(JNIEnv *env, jobject,
                                                          jlong handle,
                                                          jstring path) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->ctx || !path)
    return JNI_FALSE;
  const char *c_path = env->GetStringUTFChars(path, nullptr);
  if (!c_path)
    return JNI_FALSE;
  const std::string file_path(c_path);
  env->ReleaseStringUTFChars(path, c_path);

  std::lock_guard<std::mutex> lock(session->mu);
  llama_memory_clear(llama_get_memory(session->ctx), true);
  session->prev_tokens.clear();
  const size_t n_ctx = llama_n_ctx(session->ctx);
  std::vector<llama_token> buf(n_ctx);
  size_t n_out = 0;
  const size_t read = llama_state_seq_load_file(
      session->ctx, file_path.c_str(), 0, buf.data(), buf.size(), &n_out);
  if (read == 0 || n_out == 0 || n_out > buf.size()) {
    llama_memory_clear(llama_get_memory(session->ctx), true);
    session->prev_tokens.clear();
    return JNI_FALSE;
  }
  session->prev_tokens.assign(buf.begin(), buf.begin() + n_out);
  return JNI_TRUE;
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

JNIEXPORT jfloat JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeLastTokenLogProb(JNIEnv *, jobject,
                                                                 jlong handle) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  return session ? session->last_logp.load() : NAN;
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

// "ncpu=8;big=4;features=NEON=1 ...;sig=cap,0:495,..." - what Kotlin needs to plan and cache the tuning.
JNIEXPORT jstring JNICALL Java_com_jarvis_edge_brain_LlamaJniEngine_nativeCpuInfo(JNIEnv *env, jclass) {
  ensure_backend_init();
  const cputopo::Topology &t = cputopo::get();
  std::string s = "ncpu=" + std::to_string(t.ncpu) + ";big=" + std::to_string(t.top_class) +
                  ";features=" + g_cpu_features + ";sig=" + t.signature;
  return env->NewStringUTF(s.c_str());
}

JNIEXPORT void JNICALL Java_com_jarvis_edge_brain_LlamaJniEngine_nativeCancelBench(JNIEnv *, jclass) {
  g_bench_cancel.store(true);
}

// Times k threads on the k fastest cores on a small throw-away context (the live conversation cache is untouched).
// Returns {prefill ms/token, generate ms/token}; {} if it failed or ran past budgetMs; {-1} if cancelled.
JNIEXPORT jdoubleArray JNICALL Java_com_jarvis_edge_brain_LlamaJniEngine_nativeBenchThreads(
    JNIEnv *env, jobject, jlong handle, jint k, jlong budgetMs) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->model || k < 1) return env->NewDoubleArray(0);
  std::lock_guard<std::mutex> lock(session->mu);
  g_bench_cancel.store(false);

  llama_context_params cp = llama_context_default_params();
  cp.n_ctx = 128;
  cp.n_batch = 32;
  cp.n_ubatch = 32;
  cp.n_threads = k;
  cp.n_threads_batch = k;
  cp.no_perf = true;
  cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
  llama_context *ctx = llama_init_from_model(session->model, cp);
  if (!ctx) return env->NewDoubleArray(0);

  const llama_vocab *vocab = llama_model_get_vocab(session->model);
  std::string text;
  for (int i = 0; i < 4; ++i) text += "The quick brown fox jumps over the lazy dog while the clock on the wall ticks. ";
  std::vector<llama_token> toks(256);
  int n = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), toks.data(),
                         static_cast<int32_t>(toks.size()), true, false);
  ggml_threadpool_t tp = n >= 40 ? create_big_core_threadpool(k, k) : nullptr;
  if (!tp) {
    llama_free(ctx);
    return env->NewDoubleArray(0);
  }
  toks.resize(32);

  cpu_set_t prev;
  const bool restore = sched_getaffinity(0, sizeof(prev), &prev) == 0;
  cpu_set_t mask = cputopo::top_cores_mask(k);
  sched_setaffinity(0, sizeof(mask), &mask);
  llama_attach_threadpool(ctx, tp, tp);
  TuneDeadline deadline{std::chrono::steady_clock::now() + std::chrono::milliseconds(budgetMs)};
  llama_set_abort_callback(ctx, tune_abort_cb, &deadline);

  bool ok = llama_decode(ctx, llama_batch_get_one(toks.data(), static_cast<int32_t>(toks.size()))) == 0;
  // Prefill is timed separately from a cold first pass, so run it twice and keep the second.
  double prefill = 0;
  if (ok) {
    llama_memory_clear(llama_get_memory(ctx), true);
    auto t0 = std::chrono::steady_clock::now();
    ok = llama_decode(ctx, llama_batch_get_one(toks.data(), static_cast<int32_t>(toks.size()))) == 0;
    prefill = ms_since(t0) / static_cast<double>(toks.size());
  }
  const int kWarm = 2, kTimed = 6;
  double gen = 0;
  for (int i = 0; ok && i < kWarm + kTimed; ++i) {
    llama_token t = toks[i];
    auto g0 = std::chrono::steady_clock::now();
    ok = llama_decode(ctx, llama_batch_get_one(&t, 1)) == 0;
    if (i >= kWarm) gen += ms_since(g0);
  }
  const bool cancelled = g_bench_cancel.load() && !deadline.timed_out;
  if (deadline.timed_out) ok = false;

  llama_set_abort_callback(ctx, nullptr, nullptr);
  llama_detach_threadpool(ctx);
  llama_free(ctx);
  threadpool_free(tp);
  if (restore) sched_setaffinity(0, sizeof(prev), &prev);

  if (cancelled) {
    jdoubleArray out = env->NewDoubleArray(1);
    const jdouble v = -1;
    env->SetDoubleArrayRegion(out, 0, 1, &v);
    return out;
  }
  if (!ok) return env->NewDoubleArray(0);
  jdoubleArray out = env->NewDoubleArray(2);
  const jdouble vals[2] = {prefill, gen / kTimed};
  env->SetDoubleArrayRegion(out, 0, 2, vals);
  return out;
}

// Applies tuned thread counts to the live context (generate uses the decode fastest cores, prompt reading batch).
JNIEXPORT jboolean JNICALL Java_com_jarvis_edge_brain_LlamaJniEngine_nativeSetThreads(
    JNIEnv *, jobject, jlong handle, jint decode, jint batch) {
  auto *session = reinterpret_cast<LlmSession *>(handle);
  if (!session || !session->ctx || decode < 1 || batch < 1) return JNI_FALSE;
  std::lock_guard<std::mutex> lock(session->mu);
  const int previous_cores = cputopo::llm_cores();
  cputopo::set_llm_cores(std::max(decode, batch));
  ggml_threadpool_t tp = session_pool(session, decode);
  ggml_threadpool_t tp_batch = tp ? session_pool(session, batch) : nullptr;
  if (!tp || !tp_batch) {
    cputopo::set_llm_cores(previous_cores);
    return JNI_FALSE;
  }
  llama_detach_threadpool(session->ctx);
  session->threadpool = tp;
  session->threadpool_batch = tp_batch;
  llama_set_n_threads(session->ctx, decode, batch);
  llama_attach_threadpool(session->ctx, tp, tp_batch);
  __android_log_print(ANDROID_LOG_INFO, kLogTag, "threads set: generate %d, prefill %d", decode, batch);
  return JNI_TRUE;
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
    free_session_pools(session);
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
  const int vlm_threads = std::min(4, cputopo::llm_cores());
  cparams.n_threads = vlm_threads;
  cparams.n_threads_batch = vlm_threads;
  cparams.no_perf = true;
  llama_context *vlm_ctx = llama_init_from_model(vlm_model, cparams);
  ggml_threadpool_t tp = create_big_core_threadpool(vlm_threads);
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
    threadpool_free(tp);
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
