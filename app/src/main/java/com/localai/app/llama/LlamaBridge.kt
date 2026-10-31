package com.localai.app.llama

import android.util.Log

/**
 * Thin JNI wrapper over llama.cpp.
 * Native method implementations live in `native-lib.cpp`.
 */
object LlamaBridge {

    private const val TAG = "LlamaBridge"

    init {
        try {
            System.loadLibrary("localai")
            Log.i(TAG, "native lib loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "failed to load native lib", e)
        }
    }

    /** Loads a GGUF model from an absolute file path. Returns true on success. */
    external fun initModel(modelPath: String, nThreads: Int = 4): Boolean

    /** Frees the currently-loaded model and context. */
    external fun freeModel()

    /** Frees the vision projector context. */
    external fun freeMmproj()

    /** Returns the media marker string the loaded mmproj expects. */
    external fun getMediaMarker(): String

    /** True if a model is currently loaded and ready. */
    external fun isModelLoaded(): Boolean

    /** Synchronous generation. Returns the full response string. */
    external fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int
    ): String

    /**
     * Loads a vision projector (mmproj) file. Returns true once real mtmd
     * support is vendored; currently returns false (stub).
     */
    external fun loadMmproj(mmprojPath: String, nThreads: Int = 4): Boolean

    /** True if a vision-capable model + projector are both loaded. */
    external fun isVisionCapable(): Boolean

    /**
     * Generate with image inputs. `imagePaths` are absolute filesystem paths.
     * Until mtmd is wired up, this falls back to text-only generation.
     */
    external fun generateWithImages(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        imageBuffers: Array<ByteArray>
    ): String

    /** Registers a callback whose `onNativeProgress(F)V` is invoked during model load. */
    external fun setProgressCallback(callback: ProgressCallback?)

    /** Registers a callback whose `onToken(String)V` is invoked for each generated token. */
    external fun setTokenCallback(callback: TokenCallback?)

    /**
     * Formats a conversation using the loaded model's own chat template.
     * Must be called AFTER initModel(). Returns "" if no model is loaded.
     */
    external fun applyChatTemplate(
        roles: Array<String>,
        contents: Array<String>,
        addAssistant: Boolean
    ): String

    /** Implement this interface to receive load progress (0.0 .. 1.0). */
    interface ProgressCallback {
        fun onNativeProgress(progress: Float)
    }

    /** Implement this interface to receive streaming tokens during generation. */
    interface TokenCallback {
        fun onToken(token: String)
    }
}
