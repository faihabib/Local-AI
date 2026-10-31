#include <jni.h>
#include <android/log.h>
#include <string>
#include <mutex>
#include <cstring>
#include <vector>
#include <cstdint>

#include "stable-diffusion.h"

#define LOG_TAG "SD-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static sd_ctx_t* g_sd_ctx = nullptr;
static std::mutex g_sd_mutex;

// JNI plumbing for progress reporting
extern JavaVM* g_jvm;  // defined in native-lib.cpp
static jobject g_progress_obj = nullptr;
static jmethodID g_progress_mid = nullptr;

// Called by SD on a worker thread every step
static void on_sd_progress(int step, int steps, float time, void* /*data*/) {
    if (!g_jvm || !g_progress_obj || !g_progress_mid) return;
    JNIEnv* env = nullptr;
    bool attach = false;
    if (g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attach = true;
    }
    if (env) env->CallVoidMethod(g_progress_obj, g_progress_mid, (jint)step, (jint)steps);
    if (attach) g_jvm->DetachCurrentThread();
}

// JNI_OnLoad is defined in native-lib.cpp — it sets g_jvm globally.

extern "C" {

// ============================================================
// Init SD model + VAE + CLIP. LoRA is loaded separately at generation time.
// ============================================================
JNIEXPORT jboolean JNICALL
Java_com_localai_app_llama_StableDiffusionBridge_initSDModel(
        JNIEnv* env, jobject /*thiz*/,
        jstring jModelPath, jstring jVaePath, jstring jClipPath) {

    std::lock_guard<std::mutex> lock(g_sd_mutex);

    // Clean up previous
    if (g_sd_ctx) {
        free_sd_ctx(g_sd_ctx);
        g_sd_ctx = nullptr;
    }

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);
    const char* vaePath   = jVaePath   ? env->GetStringUTFChars(jVaePath,   nullptr) : nullptr;
    const char* clipPath  = jClipPath  ? env->GetStringUTFChars(jClipPath,  nullptr) : nullptr;

    LOGI("SD init: model=%s vae=%s clip=%s",
         modelPath,
         vaePath ? vaePath : "(none)",
         clipPath ? clipPath : "(none)");

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path  = modelPath;
    params.vae_path    = vaePath;
    params.clip_l_path = clipPath;
    // Use 6 of 8 cores — leaves headroom for UI so phone stays responsive
    params.n_threads   = 6;
    params.flash_attn  = true;
    params.lora_apply_mode = LORA_APPLY_AT_RUNTIME;

    sd_set_progress_callback(on_sd_progress, nullptr);

    g_sd_ctx = new_sd_ctx(&params);

    env->ReleaseStringUTFChars(jModelPath, modelPath);
    if (vaePath)  env->ReleaseStringUTFChars(jVaePath,  vaePath);
    if (clipPath) env->ReleaseStringUTFChars(jClipPath, clipPath);

    if (!g_sd_ctx) {
        LOGE("new_sd_ctx failed");
        return JNI_FALSE;
    }

    LOGI("SD model loaded OK");
    return JNI_TRUE;
}

// ============================================================
// Free SD model
// ============================================================
JNIEXPORT void JNICALL
Java_com_localai_app_llama_StableDiffusionBridge_freeSDModel(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> lock(g_sd_mutex);
    if (g_sd_ctx) {
        free_sd_ctx(g_sd_ctx);
        g_sd_ctx = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_localai_app_llama_StableDiffusionBridge_isSDModelLoaded(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    return g_sd_ctx ? JNI_TRUE : JNI_FALSE;
}

// ============================================================
// Generate an image.
//
// Returns a byte array with header:
//   [0..3]  int32 width  (little-endian)
//   [4..7]  int32 height
//   [8..11] int32 channels
//   [12..]  raw RGBA/RGB pixel data
//
// Kotlin side reconstructs this into a Bitmap.
// ============================================================
JNIEXPORT void JNICALL
Java_com_localai_app_llama_StableDiffusionBridge_setSDProgressCallback(
        JNIEnv* env, jobject /*thiz*/, jobject callback) {
    if (g_progress_obj) {
        env->DeleteGlobalRef(g_progress_obj);
        g_progress_obj = nullptr;
        g_progress_mid = nullptr;
    }
    if (callback) {
        g_progress_obj = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_progress_mid = env->GetMethodID(cls, "onStepProgress", "(II)V");
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_localai_app_llama_StableDiffusionBridge_generateImage(
        JNIEnv* env, jobject /*thiz*/,
        jstring jPrompt, jstring jNegative,
        jint jWidth, jint jHeight,
        jint jSteps, jfloat jCfg,
        jint jSeed,
        jstring jLoraPath, jfloat jLoraScale) {

    std::lock_guard<std::mutex> lock(g_sd_mutex);

    if (!g_sd_ctx) {
        LOGE("generateImage: no model loaded");
        return nullptr;
    }

    const char* prompt    = env->GetStringUTFChars(jPrompt,   nullptr);
    const char* negative  = env->GetStringUTFChars(jNegative, nullptr);
    const char* loraPath  = jLoraPath ? env->GetStringUTFChars(jLoraPath, nullptr) : nullptr;

    // ---- LoRA (optional) ----
    sd_lora_t lora;
    lora.is_high_noise = false;
    lora.multiplier    = jLoraScale;
    lora.path          = loraPath;

    // ---- Image generation params ----
    sd_img_gen_params_t genParams;
    sd_img_gen_params_init(&genParams);
    genParams.prompt          = prompt;
    genParams.negative_prompt = negative;
    genParams.width           = jWidth;
    genParams.height          = jHeight;
    genParams.seed            = (int64_t) jSeed;
    genParams.batch_count     = 1;

    if (loraPath && loraPath[0] != '\0') {
        genParams.loras      = &lora;
        genParams.lora_count = 1;
        LOGI("Applying LoRA: %s (multiplier=%.2f)", loraPath, jLoraScale);
    }

    genParams.sample_params.sample_steps   = jSteps;
    genParams.sample_params.guidance.txt_cfg = jCfg;

    LOGI("Generate: %dx%d steps=%d cfg=%.2f seed=%d", jWidth, jHeight, jSteps, jCfg, jSeed);

    sd_image_t* images   = nullptr;
    int         numImages = 0;
    bool ok = generate_image(g_sd_ctx, &genParams, &images, &numImages);

    env->ReleaseStringUTFChars(jPrompt,   prompt);
    env->ReleaseStringUTFChars(jNegative, negative);
    if (loraPath) env->ReleaseStringUTFChars(jLoraPath, loraPath);

    if (!ok || !images || numImages == 0) {
        LOGE("generate_image returned no images");
        return nullptr;
    }

    const sd_image_t& img = images[0];
    int32_t w = (int32_t) img.width;
    int32_t h = (int32_t) img.height;
    int32_t c = (int32_t) img.channel;

    LOGI("Image produced: %dx%d x %d channels", w, h, c);

    size_t pixelSize = (size_t) w * h * c;
    size_t totalSize = 12 + pixelSize;

    std::vector<uint8_t> buf(totalSize);
    std::memcpy(buf.data(),      &w, 4);
    std::memcpy(buf.data() + 4,  &h, 4);
    std::memcpy(buf.data() + 8,  &c, 4);
    std::memcpy(buf.data() + 12, img.data, pixelSize);

    jbyteArray result = env->NewByteArray((jsize) totalSize);
    if (result) {
        env->SetByteArrayRegion(result, 0, (jsize) totalSize,
                                reinterpret_cast<const jbyte*>(buf.data()));
    }

    free_sd_images(images, numImages);
    return result;
}

} // extern "C"
