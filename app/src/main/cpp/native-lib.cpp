#include <jni.h>
#include <csignal>
#include <unistd.h>
#include <fcntl.h>
#include <ctime>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>

#include "llama.h"

#define LOG_TAG "LocalAI-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct llama_model*   g_model = nullptr;
struct llama_context* g_ctx   = nullptr;
const struct llama_vocab* g_vocab = nullptr;
static int g_threads = 4;   // CPU threads for inference
static std::string g_mmproj_path;   // vision projector (empty = text-only)
std::mutex g_mutex;

// JNI plumbing for the load-progress callback
JavaVM* g_jvm = nullptr;  // shared with sd-bridge.cpp
static jobject  g_progress_obj = nullptr;
static jmethodID g_progress_mid = nullptr;

// JNI plumbing for the streaming token callback
static jobject  g_token_obj = nullptr;
static jmethodID g_token_mid = nullptr;

// Called by llama.cpp on a background thread.
// NOTE: signature must match `typedef bool (*llama_progress_callback)(float, void*)`.
// Returning true keeps loading; return false to abort.
static bool on_load_progress(float progress, void* /*user_data*/) {
    if (!g_jvm || !g_progress_obj || !g_progress_mid) return true;
    JNIEnv* env = nullptr;
    bool attach = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
        attach = true;
    }
    if (env) env->CallVoidMethod(g_progress_obj, g_progress_mid, (jfloat)progress);
    if (attach) g_jvm->DetachCurrentThread();
    return true;
}

// ---- Native signal handler: writes crash to /sdcard/Download ----
static void write_native_crash(int sig, const char* where) {
    const char* path = "/sdcard/Download/localai-native-crash.txt";
    int fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        // Fallback — app-private path
        path = "/data/data/com.localai.app/files/localai-native-crash.txt";
        fd = ::open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    }
    if (fd < 0) return;

    char buf[512];
    time_t now = time(nullptr);
    struct tm* tm_info = localtime(&now);
    char ts[64];
    strftime(ts, sizeof(ts), "%Y-%m-%d %H:%M:%S", tm_info);

    int n = snprintf(buf, sizeof(buf),
        "=== LocalAI NATIVE CRASH ===\n"
        "Time: %s\n"
        "Signal: %d (%s)\n"
        "Location: %s\n"
        "=== END ===\n",
        ts, sig,
        sig == SIGSEGV ? "SIGSEGV" :
        sig == SIGABRT ? "SIGABRT" :
        sig == SIGBUS  ? "SIGBUS"  :
        sig == SIGFPE  ? "SIGFPE"  :
        sig == SIGILL  ? "SIGILL"  : "UNKNOWN",
        where);
    if (n > 0) ::write(fd, buf, n);
    ::close(fd);
}

static void native_crash_handler(int sig, siginfo_t* info, void* /*ctx*/) {
    char where[128];
    snprintf(where, sizeof(where), "addr=%p", info ? info->si_addr : nullptr);
    write_native_crash(sig, where);
    // Re-raise to let the default handler kill the process cleanly
    signal(sig, SIG_DFL);
    raise(sig);
}

static void install_native_crash_handler() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = native_crash_handler;
    sa.sa_flags = SA_SIGINFO;
    sigaction(SIGSEGV, &sa, nullptr);
    sigaction(SIGABRT, &sa, nullptr);
    sigaction(SIGBUS,  &sa, nullptr);
    sigaction(SIGFPE,  &sa, nullptr);
    sigaction(SIGILL,  &sa, nullptr);
    LOGI("Native crash handler installed");
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    install_native_crash_handler();
    return JNI_VERSION_1_6;
}

// ---- Tokenize helper ----
static std::vector<llama_token> tokenize(const std::string& text, bool add_bos) {
    int n = -llama_tokenize(g_vocab, text.c_str(), (int)text.size(),
                            nullptr, 0, add_bos, true);
    std::vector<llama_token> tokens(n);
    if (n > 0) {
        llama_tokenize(g_vocab, text.c_str(), (int)text.size(),
                       tokens.data(), n, add_bos, true);
    }
    return tokens;
}

// ---- Detokenize helper ----
static std::string token_to_piece(llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, true);
    if (n < 0) return "";
    return std::string(buf, n);
}


// ---- Helper: reset the KV cache by recreating the context ----
// Called with g_mutex held. Cheap (~20-100 ms for 1B models).
bool reset_llama_context_locked() {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (!g_model) return false;
    auto cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_batch         = 512;
    cparams.n_threads       = g_threads;
    cparams.n_threads_batch = g_threads;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) { LOGE("reset_context: llama_init_from_model failed"); return false; }
    return true;
}


extern "C" {

// ============================================================
// Lifecycle
// ============================================================

JNIEXPORT jboolean JNICALL
Java_com_localai_app_llama_LlamaBridge_initModel(
        JNIEnv* env, jobject /*thiz*/, jstring jpath, jint jthreads) {

    std::lock_guard<std::mutex> lock(g_mutex);

    // Clean up previous model
    if (g_ctx)   { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    LOGI("Loading model: %s", path);

    llama_backend_init();

    auto mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;   // CPU only

    // Wire up progress reporting if the caller provided a callback.
    // The Kotlin side passes an object with a method `onNativeProgress(F)V`.
    if (g_progress_obj && g_progress_mid) {
        mparams.progress_callback = on_load_progress;
        mparams.progress_callback_user_data = nullptr;
    }

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_model) {
        LOGE("llama_model_load_from_file failed");
        return JNI_FALSE;
    }

    auto cparams = llama_context_default_params();
    cparams.n_ctx = 4096;
    cparams.n_batch         = 512;
    // 6 of 8 cores — LLM chat and SD both benefit
    cparams.n_threads       = g_threads;
    cparams.n_threads_batch = g_threads;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("llama_init_from_model failed");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    g_vocab = llama_model_get_vocab(g_model);
    LOGI("Model loaded OK");
    return JNI_TRUE;
}

// Apply the model's built-in chat template to an array of messages.
// Returns the fully formatted prompt string ready for tokenization.
JNIEXPORT jstring JNICALL
Java_com_localai_app_llama_LlamaBridge_applyChatTemplate(
        JNIEnv* env, jobject /*thiz*/,
        jobjectArray jroles, jobjectArray jcontents, jboolean jAddAssistant) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(jroles);
    if (n == 0) return env->NewStringUTF("");

    std::vector<std::string> rolesStore(n);
    std::vector<std::string> contentsStore(n);
    std::vector<llama_chat_message> msgs(n);

    for (jsize i = 0; i < n; i++) {
        jstring jr = (jstring) env->GetObjectArrayElement(jroles, i);
        jstring jc = (jstring) env->GetObjectArrayElement(jcontents, i);

        const char* r = env->GetStringUTFChars(jr, nullptr);
        const char* c = env->GetStringUTFChars(jc, nullptr);

        rolesStore[i]    = r ? r : "user";
        contentsStore[i] = c ? c : "";

        env->ReleaseStringUTFChars(jr, r);
        env->ReleaseStringUTFChars(jc, c);
        env->DeleteLocalRef(jr);
        env->DeleteLocalRef(jc);

        msgs[i].role    = rolesStore[i].c_str();
        msgs[i].content = contentsStore[i].c_str();
    }

    // Get the template string embedded in the GGUF metadata.
    const char* tmpl = llama_model_chat_template(g_model, nullptr);

    // First call with null buffer to learn the required size.
    int32_t needed = llama_chat_apply_template(
            tmpl, msgs.data(), (size_t) n, (bool) jAddAssistant, nullptr, 0);

    if (needed <= 0) {
        LOGE("applyChatTemplate: template failed, needed=%d", needed);
        return env->NewStringUTF("");
    }

    std::vector<char> buf(needed + 1, 0);
    int32_t written = llama_chat_apply_template(
            tmpl, msgs.data(), (size_t) n, (bool) jAddAssistant, buf.data(), needed);

    if (written <= 0) {
        LOGE("applyChatTemplate: second call failed, written=%d", written);
        return env->NewStringUTF("");
    }

    LOGI("applyChatTemplate: %d messages -> %d bytes", n, written);
    return env->NewStringUTF(buf.data());
}

// Register the Kotlin object whose onNativeProgress(F)V will be invoked
// during model loading. Pass null to unregister.
JNIEXPORT void JNICALL
Java_com_localai_app_llama_LlamaBridge_setProgressCallback(
        JNIEnv* env, jobject /*thiz*/, jobject callback) {
    if (g_progress_obj) {
        env->DeleteGlobalRef(g_progress_obj);
        g_progress_obj = nullptr;
        g_progress_mid = nullptr;
    }
    if (callback) {
        g_progress_obj = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_progress_mid = env->GetMethodID(cls, "onNativeProgress", "(F)V");
    }
}

JNIEXPORT void JNICALL
Java_com_localai_app_llama_LlamaBridge_freeModel(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx)   { llama_free(g_ctx);       g_ctx   = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_mmproj_path.clear();
    LOGI("Model freed");
}

JNIEXPORT jboolean JNICALL
Java_com_localai_app_llama_LlamaBridge_isModelLoaded(JNIEnv* /*env*/, jobject /*thiz*/) {
    return (g_model && g_ctx) ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// Generation (synchronous, returns full string).
// Streaming will be added later via a callback.
// ============================================================

JNIEXPORT void JNICALL
Java_com_localai_app_llama_LlamaBridge_setTokenCallback(JNIEnv* env, jobject /*thiz*/, jobject cb) {
    if (g_token_obj) {
        env->DeleteGlobalRef(g_token_obj);
        g_token_obj = nullptr;
        g_token_mid = nullptr;
    }
    if (cb) {
        g_token_obj = env->NewGlobalRef(cb);
        jclass cls = env->GetObjectClass(cb);
        g_token_mid = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    }
}

JNIEXPORT jstring JNICALL
Java_com_localai_app_llama_LlamaBridge_generate(
        JNIEnv* env, jobject /*thiz*/, jstring jprompt, jint maxTokens,
        jfloat temperature, jfloat topP, jint topK) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) {
        return env->NewStringUTF("[error: model not loaded]");
    }

    // Reset the KV cache before every turn so previous prompts don't fill
    // n_ctx and leave the context in a state where llama_decode() fails.
    if (!reset_llama_context_locked()) {
        return env->NewStringUTF("[error: could not reset context]");
    }

    const char* prompt_c = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_c);
    env->ReleaseStringUTFChars(jprompt, prompt_c);

    // Tokenize the prompt (add BOS)
    auto tokens = tokenize(prompt, true);
    if (tokens.empty()) {
        return env->NewStringUTF("");
    }

    // Truncate the prompt so it fits within n_ctx, reserving room for the reply.
    // Keep BOS + the most recent tokens — the user's question is usually at the
    // end of an attached-file block, so tail-keeping preserves what matters.
    const int n_ctx = 4096;
    int reserved = maxTokens + 8;
    if (reserved >= n_ctx - 32) reserved = n_ctx - 32;   // safety
    int max_prompt = n_ctx - reserved;
    if ((int)tokens.size() > max_prompt) {
        int drop = (int)tokens.size() - max_prompt;
        LOGI("Prompt %d tokens > %d — dropping %d oldest non-BOS tokens",
             (int)tokens.size(), max_prompt, drop);
        tokens.erase(tokens.begin() + 1, tokens.begin() + 1 + drop);
    }

    // Process the prompt in batches
    const int n_batch = 512;
    for (size_t i = 0; i < tokens.size(); i += n_batch) {
        int cur = std::min<int>((int)(tokens.size() - i), n_batch);
        llama_batch batch = llama_batch_get_one(tokens.data() + i, cur);
        if (llama_decode(g_ctx, batch) != 0) {
            return env->NewStringUTF("[error: decode failed]");
        }
    }

    // Build a sampler chain: top-k + top-p + temperature
    auto sparams = llama_sampler_chain_default_params();
    struct llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0.f ? topP : 0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature > 0.f ? temperature : 0.8f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;
    std::string utf8_buf;   // holds bytes whose UTF-8 sequence is incomplete
    const int prompt_len = (int)tokens.size();

    auto emit_to_callback = [&](const std::string& bytes) {
        if (!g_jvm || !g_token_obj || !g_token_mid || bytes.empty()) return;
        JNIEnv* tenv = nullptr;
        bool tattach = false;
        if (g_jvm->GetEnv((void**)&tenv, JNI_VERSION_1_6) != JNI_OK) {
            if (g_jvm->AttachCurrentThread(&tenv, nullptr) == JNI_OK) tattach = true;
        }
        if (tenv) {
            jstring jp = tenv->NewStringUTF(bytes.c_str());
            tenv->CallVoidMethod(g_token_obj, g_token_mid, jp);
            tenv->DeleteLocalRef(jp);
        }
        if (tattach) g_jvm->DetachCurrentThread();
    };

    // Return the byte count that is safe to hand to NewStringUTF — i.e. up to
    // the last fully-formed UTF-8 sequence. Trailing partial bytes (the first
    // half of a 2/3/4-byte codepoint) are held for the next call.
    auto utf8_safe_len = [](const std::string& s) -> size_t {
        size_t n = s.size();
        if (n == 0) return 0;
        size_t k = 0;
        while (k < 4 && k < n) {
            unsigned char c = (unsigned char)s[n - 1 - k];
            if ((c & 0x80) == 0) return n - k;              // ASCII byte — complete
            if ((c & 0xC0) == 0xC0) {                        // lead byte
                int need = ((c & 0xE0) == 0xC0) ? 2
                         : ((c & 0xF0) == 0xE0) ? 3
                         : ((c & 0xF8) == 0xF0) ? 4 : 1;
                if (k + 1 >= (size_t)need) return n;         // full sequence present
                return n - 1 - k;                            // hold from this lead byte
            }
            k++;  // continuation byte (10xxxxxx) — keep walking back
        }
        return n;
    };

    for (int i = 0; i < maxTokens; ++i) {
        // Stop if we're about to overflow the context window
        if (prompt_len + i + 1 >= n_ctx) {
            LOGI("Hit context limit after %d generated tokens", i);
            break;
        }
        llama_token next = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, next)) break;

        std::string piece = token_to_piece(next);
        result += piece;

        // Stream only complete UTF-8 sequences. llama.cpp splits multi-byte
        // codepoints across tokens, so a token may end mid-sequence.
        utf8_buf += piece;
        size_t emitLen = utf8_safe_len(utf8_buf);
        if (emitLen > 0) {
            emit_to_callback(utf8_buf.substr(0, emitLen));
            utf8_buf.erase(0, emitLen);
        }

        // Feed the token back in
        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
    }

    // Flush whatever remains — at end of generation it's a complete sequence.
    if (!utf8_buf.empty()) emit_to_callback(utf8_buf);

    llama_sampler_free(smpl);

    // Trim any trailing incomplete sequence from the returned string too,
    // otherwise the final NewStringUTF would abort the same way.
    size_t finalLen = utf8_safe_len(result);
    if (finalLen < result.size()) result.resize(finalLen);

    return env->NewStringUTF(result.c_str());
}


// ============================================================


} // extern "C"
