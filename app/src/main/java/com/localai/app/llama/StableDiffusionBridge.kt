package com.localai.app.llama

import android.util.Log

object StableDiffusionBridge {

    private const val TAG = "SDBridge"

    init {
        try {
            System.loadLibrary("localai")
            Log.i(TAG, "native lib loaded")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "failed to load native lib", e)
        }
    }

    external fun initSDModel(modelPath: String, vaePath: String, clipPath: String): Boolean
    external fun freeSDModel()
    external fun isSDModelLoaded(): Boolean

    external fun generateImage(
        prompt: String,
        negativePrompt: String,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Int,
        loraPath: String,
        loraScale: Float
    ): ByteArray?

    external fun setSDProgressCallback(callback: SDProgressCallback?)

    interface SDProgressCallback {
        fun onStepProgress(step: Int, total: Int)
    }
}
