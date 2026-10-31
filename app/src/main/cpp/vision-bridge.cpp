#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <mutex>
#include <cstring>
#include <cstdint>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define VLOG_TAG "LocalAI-Vision"
#define VLOGI(...) __android_log_print(ANDROID_LOG_INFO,  VLOG_TAG, __VA_ARGS__)
#define VLOGE(...) __android_log_print(ANDROID_LOG_ERROR, VLOG_TAG, __VA_ARGS__)

extern struct llama_model*   g_model;
extern struct llama_context* g_ctx;
extern const struct llama_vocab* g_vocab;
extern std::mutex g_mutex;

static mtmd_context* g_mtmd = nullptr;

bool reset_llama_context_locked();

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_localai_app_llama_LlamaBridge_loadMmproj(
        JNIEnv* env, jobject /*thiz*/, jstring jpath, jint nThreads) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model) {
        VLOGE("loadMmproj: no text model loaded");
        return JNI_FALSE;
    }

    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
    }

    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) {
        VLOGE("loadMmproj: null path");
        return JNI_FALSE;
    }

    auto cparams = mtmd_context_params_default();
    cparams.use_gpu    = false;
    cparams.n_threads  = (nThreads > 0) ? nThreads : 4;
    cparams.print_timings = false;
    cparams.warmup     = false;

    VLOGI("mtmd_init_from_file: %s", path);
    g_mtmd = mtmd_init_from_file(path, g_model, cparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!g_mtmd) {
        VLOGE("mtmd_init_from_file failed");
        return JNI_FALSE;
    }

    bool hasVision = mtmd_support_vision(g_mtmd);
    VLOGI("mtmd loaded OK, vision=%d", hasVision);
    return hasVision ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_localai_app_llama_LlamaBridge_isVisionCapable(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_mtmd || !g_model || !g_ctx) return JNI_FALSE;
    return mtmd_support_vision(g_mtmd) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_localai_app_llama_LlamaBridge_getMediaMarker(
        JNIEnv* env, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_mtmd) return env->NewStringUTF("");
    const char* m = mtmd_get_marker(g_mtmd);
    return env->NewStringUTF(m ? m : "");
}

JNIEXPORT void JNICALL
Java_com_localai_app_llama_LlamaBridge_freeMmproj(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mtmd) {
        mtmd_free(g_mtmd);
        g_mtmd = nullptr;
        VLOGI("mtmd freed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_localai_app_llama_LlamaBridge_generateWithImages(
        JNIEnv* env, jobject /*thiz*/,
        jstring jprompt, jint maxTokens,
        jfloat temperature, jfloat topP, jint topK,
        jobjectArray jimageBuffers) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model || !g_ctx) {
        return env->NewStringUTF("[error: model not loaded]");
    }
    if (!g_mtmd) {
        return env->NewStringUTF("[error: vision model not loaded]");
    }
    if (!mtmd_support_vision(g_mtmd)) {
        return env->NewStringUTF("[error: loaded mmproj does not support vision]");
    }

    if (!reset_llama_context_locked()) {
        return env->NewStringUTF("[error: could not reset context]");
    }

    const char* prompt_c = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(prompt_c ? prompt_c : "");
    env->ReleaseStringUTFChars(jprompt, prompt_c);

    // NOTE: The media marker (<__media__>) is now inserted on the Kotlin side
    // inside the user's message text BEFORE the chat template is applied. That
    // ensures the marker lands in the correct position within the templated
    // prompt. Do NOT prepend here — it would end up before <bos> and be
    // treated as literal text by the tokenizer.

    jsize nImages = env->GetArrayLength(jimageBuffers);
    VLOGI("generateWithImages: %d image(s), prompt len=%zu", nImages, prompt.size());

    std::vector<mtmd_bitmap*> bitmaps;
    bitmaps.reserve(nImages);

    for (jsize i = 0; i < nImages; i++) {
        jbyteArray jbuf = (jbyteArray) env->GetObjectArrayElement(jimageBuffers, i);
        jsize len = env->GetArrayLength(jbuf);
        if (len < 12) {
            VLOGE("image %d: buffer too small", i);
            env->DeleteLocalRef(jbuf);
            for (auto* b : bitmaps) mtmd_bitmap_free(b);
            return env->NewStringUTF("[error: bad image buffer]");
        }

        std::vector<jbyte> buf(len);
        env->GetByteArrayRegion(jbuf, 0, len, buf.data());
        env->DeleteLocalRef(jbuf);

        int32_t w = ((int32_t)buf[0] & 0xFF)
                  | (((int32_t)buf[1] & 0xFF) << 8)
                  | (((int32_t)buf[2] & 0xFF) << 16)
                  | (((int32_t)buf[3] & 0xFF) << 24);
        int32_t h = ((int32_t)buf[4] & 0xFF)
                  | (((int32_t)buf[5] & 0xFF) << 8)
                  | (((int32_t)buf[6] & 0xFF) << 16)
                  | (((int32_t)buf[7] & 0xFF) << 24);

        if (w <= 0 || h <= 0 || len != 12 + w * h * 3) {
            VLOGE("image %d: bad dims %dx%d, len=%d expected=%d",
                  i, w, h, len, 12 + w * h * 3);
            for (auto* b : bitmaps) mtmd_bitmap_free(b);
            return env->NewStringUTF("[error: bad image dimensions]");
        }

        const unsigned char* rgb = (const unsigned char*)(buf.data() + 12);
        mtmd_bitmap* bm = mtmd_bitmap_init((uint32_t)w, (uint32_t)h, rgb);
        if (!bm) {
            VLOGE("image %d: mtmd_bitmap_init failed", i);
            for (auto* b : bitmaps) mtmd_bitmap_free(b);
            return env->NewStringUTF("[error: bitmap alloc failed]");
        }
        bitmaps.push_back(bm);
    }

    mtmd_input_text text{};
    text.text           = prompt.c_str();
    text.text_len       = prompt.size();
    text.add_special    = true;
    text.parse_special  = true;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    if (!chunks) {
        for (auto* b : bitmaps) mtmd_bitmap_free(b);
        return env->NewStringUTF("[error: chunks alloc failed]");
    }

    int32_t tok_ret = mtmd_tokenize(
        g_mtmd,
        chunks,
        &text,
        bitmaps.empty() ? nullptr : const_cast<const mtmd_bitmap**>(bitmaps.data()),
        bitmaps.size()
    );

    for (auto* b : bitmaps) mtmd_bitmap_free(b);
    bitmaps.clear();

    if (tok_ret != 0) {
        VLOGE("mtmd_tokenize failed: %d", tok_ret);
        mtmd_input_chunks_free(chunks);
        return env->NewStringUTF("[error: tokenize failed]");
    }

    llama_pos new_n_past = 0;
    int32_t eval_ret = mtmd_helper_eval_chunks(
        g_mtmd,
        g_ctx,
        chunks,
        /*n_past*/ 0,
        /*seq_id*/ 0,
        /*n_batch*/ 512,
        /*logits_last*/ true,
        &new_n_past
    );

    mtmd_input_chunks_free(chunks);

    if (eval_ret != 0) {
        VLOGE("mtmd_helper_eval_chunks failed: %d", eval_ret);
        return env->NewStringUTF("[error: vision eval failed]");
    }

    VLOGI("vision prompt evaluated, n_past=%d", new_n_past);

    auto sparams = llama_sampler_chain_default_params();
    struct llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(topK > 0 ? topK : 40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(topP > 0.f ? topP : 0.95f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(temperature > 0.f ? temperature : 0.8f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;
    const int n_ctx_max = 2048;

    for (int i = 0; i < maxTokens; ++i) {
        if (new_n_past + 1 >= n_ctx_max) {
            VLOGI("hit ctx limit after %d tokens", i);
            break;
        }

        llama_token next = llama_sampler_sample(smpl, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, next)) break;

        char piece_buf[256];
        int np = llama_token_to_piece(g_vocab, next, piece_buf, sizeof(piece_buf), 0, true);
        if (np > 0) result.append(piece_buf, np);

        llama_batch batch = llama_batch_get_one(&next, 1);
        if (llama_decode(g_ctx, batch) != 0) break;
        new_n_past++;
    }

    llama_sampler_free(smpl);
    VLOGI("vision generation done, %zu chars", result.size());
    return env->NewStringUTF(result.c_str());
}

}
