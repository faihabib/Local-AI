package com.naman.quantallm

/**
 * JNI stub matching the symbol names in libllama_jni.so (from QuantaLLM).
 * Package + class + method names are load-bearing — do not rename.
 */
object LlamaJni {
    init {
        try { System.loadLibrary("llama_jni") } catch (_: Throwable) {}
    }

    interface StreamingCallback {
        fun onProgress(code: Int, text: String)
    }

    external fun nativeInit(modelPath: String, nThreads: Int): Int
    external fun nativeShutdown(): Int
    external fun nativeSetBackendMode(mode: Int): Boolean
    external fun nativeSetHexagonConfig(ndev: Int, nhvx: Int, verbose: Boolean, profile: Boolean): Boolean
    external fun nativeSetContextSize(n: Int): Boolean
    external fun nativeSetCacheDir(path: String)
    external fun nativeGenerateStreamingAdvanced(
        prompt: String, maxTokens: Int, temperature: Float,
        topK: Int, topP: Float, minP: Float, repeatPenalty: Float, penaltyLastN: Int,
        frequencyPenalty: Float, presencePenalty: Float, seed: Int,
        cb: StreamingCallback
    ): String
    external fun nativeGenerateStreaming(
        prompt: String, maxTokens: Int, temperature: Float, cb: StreamingCallback
    ): String
    external fun nativeAbortGeneration()
    external fun nativeGetLastError(): String
    external fun nativeGetModelInfo(): String
}
