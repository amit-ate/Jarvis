#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "ggml.h"
#include "ggml-cpu.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

constexpr const char * kLogTag = "JarvisLlamaJni";

std::once_flag g_backend_once;

void ensure_backend_init() {
    std::call_once(g_backend_once, []() {
        llama_backend_init();
    });
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
    tpp.poll = 0; // Mandatory on SDM636 Android to prevent big-core barrier spin livelock.
    for (int cpu = 4; cpu <= 7 && cpu < GGML_MAX_N_THREADS; ++cpu) {
        tpp.cpumask[cpu] = true;
    }
    tpp.strict_cpu = true;
    return ggml_threadpool_new(&tpp);
}

// Pins the calling thread to the Cortex-A73 big cores (4-7) and restores the previous
// affinity on destruction. mtmd creates its CLIP threadpool without a cpumask, so its
// workers inherit this thread's affinity; without pinning they land on A53 cores and
// every graph barrier waits on the slowest little core.
struct ScopedBigCoreAffinity {
    cpu_set_t previous{};
    bool restore = false;
    ScopedBigCoreAffinity() {
        if (sched_getaffinity(0, sizeof(previous), &previous) != 0) return;
        cpu_set_t big;
        CPU_ZERO(&big);
        for (int cpu = 4; cpu <= 7; ++cpu) CPU_SET(cpu, &big);
        restore = sched_setaffinity(0, sizeof(big), &big) == 0;
    }
    ~ScopedBigCoreAffinity() {
        if (restore) sched_setaffinity(0, sizeof(previous), &previous);
    }
};

struct LlmSession {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    ggml_threadpool_t threadpool = nullptr;
    std::vector<llama_token> prev_tokens;
    std::mutex mu;
};

int64_t now_us() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

// Compute the longest prefix length in `buf` that ends on complete valid UTF-8 codepoints.
size_t valid_utf8_prefix_len(const std::string & buf) {
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
            // Invalid leading byte; advance past it safely (replaced later if emitted).
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

std::string sanitize_for_modified_utf8(const std::string & in) {
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

bool emit_piece(JNIEnv * env, jobject callback, jmethodID on_token_mid, std::string & pending, bool flush) {
    if (pending.empty()) return true;
    const size_t emit_len = flush ? pending.size() : valid_utf8_prefix_len(pending);
    if (emit_len == 0) return true;

    std::string chunk = sanitize_for_modified_utf8(pending.substr(0, emit_len));
    pending.erase(0, emit_len);
    if (chunk.empty()) return true;

    jstring j_piece = env->NewStringUTF(chunk.c_str());
    const jboolean cont = env->CallBooleanMethod(callback, on_token_mid, j_piece);
    env->DeleteLocalRef(j_piece);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return cont == JNI_TRUE;
}

bool eval_token_range(
    llama_context * ctx,
    const std::vector<llama_token> & tokens,
    size_t start_idx,
    int32_t batch_cap
) {
    const size_t total = tokens.size();
    if (start_idx >= total) return true;

    llama_batch batch = llama_batch_init(batch_cap, 0, 1);
    for (size_t offset = start_idx; offset < total; offset += static_cast<size_t>(batch_cap)) {
        const int32_t n = static_cast<int32_t>(
            std::min<size_t>(static_cast<size_t>(batch_cap), total - offset)
        );
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

std::vector<llama_token> tokenize_text(const llama_vocab * vocab, const std::string & text, bool add_special, bool parse_special) {
    const int32_t approx = static_cast<int32_t>(text.size()) + 16;
    std::vector<llama_token> tokens(approx);
    int32_t n = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        add_special,
        parse_special
    );
    if (n < 0) {
        tokens.resize(static_cast<size_t>(-n));
        n = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            add_special,
            parse_special
        );
    }
    if (n < 0) return {};
    tokens.resize(static_cast<size_t>(n));
    return tokens;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativePinCurrentThreadToBigCores(JNIEnv *, jclass) {
    pin_to_big_cores();
}

JNIEXPORT jlong JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeLoad(
    JNIEnv * env,
    jobject,
    jstring modelPath,
    jint contextTokens
) {
    ensure_backend_init();
    pin_to_big_cores();

    const char * path_cstr = env->GetStringUTFChars(modelPath, nullptr);
    if (!path_cstr) return 0;
    std::string path_str(path_cstr);
    env->ReleaseStringUTFChars(modelPath, path_cstr);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(path_str.c_str(), mparams);
    if (!model) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to load model: %s", path_str.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(std::max<jint>(256, contextTokens));
    cparams.n_batch = 512;
    cparams.n_ubatch = 512;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    cparams.no_perf = true;

    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to create context for %s", path_str.c_str());
        llama_model_free(model);
        return 0;
    }

    ggml_threadpool_t tp = create_big_core_threadpool(4);
    if (tp) {
        llama_attach_threadpool(ctx, tp, tp);
    }

    auto * session = new LlmSession();
    session->model = model;
    session->ctx = ctx;
    session->threadpool = tp;
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jlongArray JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeGenerate(
    JNIEnv * env,
    jobject,
    jlong handle,
    jstring jPrompt,
    jstring jGrammar,
    jint maxTokens,
    jobject callback
) {
    auto * session = reinterpret_cast<LlmSession *>(handle);
    if (!session || !session->ctx || !session->model) {
        return env->NewLongArray(5);
    }

    std::lock_guard<std::mutex> lock(session->mu);
    pin_to_big_cores();

    const char * prompt_cstr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt_str = prompt_cstr ? prompt_cstr : "";
    if (prompt_cstr) env->ReleaseStringUTFChars(jPrompt, prompt_cstr);

    std::string grammar_str;
    if (jGrammar != nullptr) {
        const char * gram_cstr = env->GetStringUTFChars(jGrammar, nullptr);
        if (gram_cstr) {
            grammar_str = gram_cstr;
            env->ReleaseStringUTFChars(jGrammar, gram_cstr);
        }
    }

    const llama_vocab * vocab = llama_model_get_vocab(session->model);
    std::vector<llama_token> prompt_tokens = tokenize_text(vocab, prompt_str, true, true);
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
    const size_t min_len = std::min(session->prev_tokens.size(), prompt_tokens.size());
    while (common < min_len && session->prev_tokens[common] == prompt_tokens[common]) {
        ++common;
    }
    if (common == prompt_tokens.size() && common > 0) {
        // Always decode at least the last prompt token so logits exist for sampling.
        --common;
    }

    llama_memory_seq_rm(llama_get_memory(session->ctx), 0, static_cast<llama_pos>(common), -1);
    session->prev_tokens.resize(common);

    const int64_t t_start_us = now_us();

    if (!eval_token_range(session->ctx, prompt_tokens, common, 512)) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to evaluate prompt tokens");
        llama_memory_clear(llama_get_memory(session->ctx), true);
        session->prev_tokens.clear();
        return env->NewLongArray(5);
    }
    session->prev_tokens = prompt_tokens;

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar_str.empty()) {
        llama_sampler * gram = llama_sampler_init_grammar(vocab, grammar_str.c_str(), "root");
        if (gram) {
            llama_sampler_chain_add(smpl, gram);
        } else {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to parse GBNF grammar");
        }
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.3f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass cb_cls = env->GetObjectClass(callback);
    jmethodID on_token_mid = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)Z");
    env->DeleteLocalRef(cb_cls);

    int32_t out_count = 0;
    int64_t first_token_ms = -1;
    int64_t gen_start_us = 0;
    std::string pending_utf8;

    llama_batch one = llama_batch_init(1, 0, 1);
    const int32_t limit = std::min<int32_t>(
        maxTokens,
        std::max<int32_t>(0, static_cast<int32_t>(n_ctx) - static_cast<int32_t>(session->prev_tokens.size()) - 1)
    );

    for (int32_t step = 0; step < limit; ++step) {
        const llama_token id = llama_sampler_sample(smpl, session->ctx, -1);
        const int64_t now = now_us();
        if (first_token_ms < 0) {
            first_token_ms = (now - t_start_us) / 1000;
            gen_start_us = now;
        }
        if (llama_vocab_is_eog(vocab, id)) {
            break;
        }

        char piece_buf[256];
        const int32_t n_bytes = llama_token_to_piece(vocab, id, piece_buf, sizeof(piece_buf), 0, false);
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
        if (llama_decode(session->ctx, one) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Failed to decode token step %d", step);
            break;
        }
        session->prev_tokens.push_back(id);
    }

    emit_piece(env, callback, on_token_mid, pending_utf8, true);

    llama_batch_free(one);
    llama_sampler_free(smpl);

    const int64_t gen_elapsed_us = (gen_start_us > 0) ? std::max<int64_t>(1, now_us() - gen_start_us) : 0;
    if (first_token_ms < 0) {
        first_token_ms = (now_us() - t_start_us) / 1000;
    }

    jlong stats[5] = {
        static_cast<jlong>(prompt_tokens.size()),
        static_cast<jlong>(common),
        static_cast<jlong>(out_count),
        static_cast<jlong>(first_token_ms),
        static_cast<jlong>(gen_elapsed_us)
    };
    jlongArray out_arr = env->NewLongArray(5);
    env->SetLongArrayRegion(out_arr, 0, 5, stats);
    return out_arr;
}

JNIEXPORT void JNICALL
Java_com_jarvis_edge_brain_LlamaJniEngine_nativeUnload(JNIEnv *, jobject, jlong handle) {
    auto * session = reinterpret_cast<LlmSession *>(handle);
    if (!session) return;
    {
        std::lock_guard<std::mutex> lock(session->mu);
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
    JNIEnv * env,
    jclass,
    jstring jModelPath,
    jstring jMmprojPath,
    jbyteArray jRgbBytes,
    jint width,
    jint height,
    jstring jQuestion,
    jint maxTokens,
    jobject callback
) {
    ensure_backend_init();
    pin_to_big_cores();

    const char * m_cstr = env->GetStringUTFChars(jModelPath, nullptr);
    const char * p_cstr = env->GetStringUTFChars(jMmprojPath, nullptr);
    const char * q_cstr = env->GetStringUTFChars(jQuestion, nullptr);
    std::string model_path = m_cstr ? m_cstr : "";
    std::string mmproj_path = p_cstr ? p_cstr : "";
    std::string question = q_cstr ? q_cstr : "Describe this image concisely.";
    if (m_cstr) env->ReleaseStringUTFChars(jModelPath, m_cstr);
    if (p_cstr) env->ReleaseStringUTFChars(jMmprojPath, p_cstr);
    if (q_cstr) env->ReleaseStringUTFChars(jQuestion, q_cstr);

    const jsize rgb_len = env->GetArrayLength(jRgbBytes);
    std::vector<unsigned char> rgb(static_cast<size_t>(rgb_len));
    env->GetByteArrayRegion(jRgbBytes, 0, rgb_len, reinterpret_cast<jbyte *>(rgb.data()));

    const auto vlm_t0 = std::chrono::steady_clock::now();
    ScopedBigCoreAffinity big_core_affinity;
    auto vlm_ms = [&vlm_t0]() {
        return static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - vlm_t0).count());
    };

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model * vlm_model = llama_model_load_from_file(model_path.c_str(), mparams);
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "VLM timing: model loaded at %lld ms", vlm_ms());
    if (!vlm_model) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "VLM failed to load model %s", model_path.c_str());
        return;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 256;
    cparams.n_ubatch = 256;
    cparams.n_threads = 4;
    cparams.n_threads_batch = 4;
    cparams.no_perf = true;
    llama_context * vlm_ctx = llama_init_from_model(vlm_model, cparams);
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
    mtmd_context * mctx = vlm_ctx ? mtmd_init_from_file(mmproj_path.c_str(), vlm_model, vparams) : nullptr;
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "VLM timing: ctx+mmproj ready at %lld ms", vlm_ms());

    if (mctx && vlm_ctx) {
        mtmd_bitmap * bmp = mtmd_bitmap_init(
            static_cast<uint32_t>(width),
            static_cast<uint32_t>(height),
            rgb.data()
        );
        std::string prompt = std::string("<|im_start|>User:") + mtmd_default_marker() +
                             question + "<end_of_utterance>\nAssistant:";
        struct mtmd_input_text inp = { prompt.c_str(), prompt.size(), true, true };
        const mtmd_bitmap * bitmaps[1] = { bmp };
        mtmd_input_chunks * chunks = mtmd_input_chunks_init();

        if (bmp && mtmd_tokenize(mctx, chunks, &inp, bitmaps, 1) == 0) {
            llama_pos n_past = 0;
            const int eval_rc = mtmd_helper_eval_chunks(mctx, vlm_ctx, chunks, n_past, 0, 256, true, &n_past);
            __android_log_print(ANDROID_LOG_INFO, kLogTag,
                "VLM timing: image encode + prefill done at %lld ms (n_past=%d, rc=%d)",
                vlm_ms(), static_cast<int>(n_past), eval_rc);
            if (eval_rc == 0) {
                const llama_vocab * vocab = llama_model_get_vocab(vlm_model);
                llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
                llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.2f));
                llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
                llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

                jclass cb_cls = env->GetObjectClass(callback);
                jmethodID on_token_mid = env->GetMethodID(cb_cls, "onToken", "(Ljava/lang/String;)Z");
                env->DeleteLocalRef(cb_cls);

                llama_batch one = llama_batch_init(1, 0, 1);
                std::string pending;
                for (int32_t step = 0; step < maxTokens; ++step) {
                    const llama_token id = llama_sampler_sample(smpl, vlm_ctx, -1);
                    if (llama_vocab_is_eog(vocab, id)) break;

                    char buf[256];
                    const int32_t nb = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, false);
                    if (nb > 0) {
                        pending.append(buf, static_cast<size_t>(nb));
                        if (!emit_piece(env, callback, on_token_mid, pending, false)) break;
                    }
                    one.n_tokens = 1;
                    one.token[0] = id;
                    one.pos[0] = n_past++;
                    one.n_seq_id[0] = 1;
                    one.seq_id[0][0] = 0;
                    one.logits[0] = 1;
                    if (llama_decode(vlm_ctx, one) != 0) break;
                }
                emit_piece(env, callback, on_token_mid, pending, true);
                __android_log_print(ANDROID_LOG_INFO, kLogTag,
                    "VLM timing: generation done at %lld ms", vlm_ms());
                llama_batch_free(one);
                llama_sampler_free(smpl);
            }
        }
        mtmd_input_chunks_free(chunks);
        if (bmp) mtmd_bitmap_free(bmp);
    }

    if (mctx) mtmd_free(mctx);
    if (vlm_ctx) {
        llama_detach_threadpool(vlm_ctx);
        llama_free(vlm_ctx);
    }
    llama_model_free(vlm_model);
    if (tp) ggml_threadpool_free(tp);
}

} // extern "C"
