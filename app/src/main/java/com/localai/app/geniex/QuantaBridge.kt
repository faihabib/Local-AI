package com.localai.app.geniex

import android.util.Log
import com.naman.quantallm.LlamaJni
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object QuantaBridge {
    private const val TAG = "QuantaBridge"
    const val BACKEND_CPU = 1
    const val BACKEND_HEXAGON = 2

    @Volatile private var loaded = false
    @Volatile private var cachedTemplate: String? = null  // embedded Jinja from GGUF
    @Volatile private var fallbackName: String = ""        // filename-based fallback

    fun isModelLoaded(): Boolean = loaded

    suspend fun loadFromPath(modelPath: String, threads: Int = 4): Result<Unit> {
        return try {
            if (!LlamaJni.nativeSetBackendMode(BACKEND_HEXAGON)) {
                Log.w(TAG, "setBackendMode(HEXAGON) failed: ${LlamaJni.nativeGetLastError()}")
            }
            if (!LlamaJni.nativeSetHexagonConfig(1, 0, false, false)) {
                Log.w(TAG, "setHexagonConfig failed: ${LlamaJni.nativeGetLastError()}")
            }
            try { LlamaJni.nativeSetContextSize(2048) } catch (_: Throwable) {}
            val rc = LlamaJni.nativeInit(modelPath, threads)
            if (rc == 0) {
                loaded = true
                cachedTemplate = GgufReader.readChatTemplate(modelPath)
                fallbackName = modelPath.lowercase()
                Log.i(TAG, "loaded: $modelPath, template=${if (cachedTemplate!=null) "embedded" else "fallback"}")
                try {
                    val info = LlamaJni.nativeGetModelInfo()
                    java.io.File("/sdcard/Download/geniex_debug").mkdirs()
                    java.io.File("/sdcard/Download/geniex_debug/model-info.json").writeText(info ?: "")
                    cachedTemplate?.let {
                        java.io.File("/sdcard/Download/geniex_debug/chat_template.jinja").writeText(it)
                    }
                } catch (_: Throwable) {}
                Result.success(Unit)
            } else {
                loaded = false
                Result.failure(IllegalStateException("nativeInit rc=$rc: ${LlamaJni.nativeGetLastError()}"))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "loadFromPath failed", e); loaded = false; Result.failure(e)
        }
    }

    private fun buildPrompt(userText: String, searchBlock: String = ""): String {
        val sys = if (searchBlock.isEmpty()) {
            "You are a helpful assistant."
        } else {
            "You are a helpful assistant.\n\nWeb search results (authoritative, use these over your training data):\n$searchBlock"
        }
        // MiniJinja doesn't implement the `|` filter operator. Templates that
        // use it (Gemma, some Llama-3 variants) would render partially and
        // leak raw Jinja fragments into the prompt. Route those to the
        // filename-based fallback, which produces the correct format.
        val tmplUsable = cachedTemplate?.takeIf { !it.contains("|") }
        tmplUsable?.let { tmpl ->
            try {
                val ctx = mapOf(
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to sys),
                        mapOf("role" to "user",   "content" to userText),
                    ),
                    "add_generation_prompt" to true,
                    "bos_token" to "",
                    "eos_token" to "",
                    "enable_thinking" to false
                )
                val out = MiniJinja(tmpl).render(ctx)
                Log.d(TAG, "embedded template rendered (${out.length} chars)")
                return out
            } catch (e: Throwable) {
                Log.w(TAG, "embedded template render failed, falling back", e)
            }
        }
        return fallbackTemplate(userText, sys)
    }

    private fun fallbackTemplate(userText: String, sys: String): String {
        val n = fallbackName
        return when {
            n.contains("phi") ->
                "<|system|>\n$sys<|end|>\n<|user|>\n$userText<|end|>\n<|assistant|>\n"
            n.contains("llama-3") || n.contains("llama3") || n.contains("llama_3") ->
                "<|start_header_id|>system<|end_header_id|>\n\n$sys<|eot_id|>" +
                "<|start_header_id|>user<|end_header_id|>\n\n$userText<|eot_id|>" +
                "<|start_header_id|>assistant<|end_header_id|>\n\n"
            n.contains("gemma") ->
                "<start_of_turn>user\n$sys\n\n$userText<end_of_turn>\n<start_of_turn>model\n"
            n.contains("mistral") || n.contains("mixtral") ->
                "[INST] $sys\n\n$userText [/INST]"
            n.contains("qwen") ->
                "<|im_start|>system\n$sys<|im_end|>\n" +
                "<|im_start|>user\n$userText<|im_end|>\n" +
                "<|im_start|>assistant\n<think>\n\n</think>\n\n"
            else ->
                "<|im_start|>system\n$sys<|im_end|>\n" +
                "<|im_start|>user\n$userText<|im_end|>\n" +
                "<|im_start|>assistant\n"
        }
    }

    fun generate(prompt: String, searchBlock: String = "", maxTokens: Int = 512, temperature: Float = 0.7f): Flow<String> = callbackFlow {
        if (!loaded) { close(IllegalStateException("no model loaded")); return@callbackFlow }
        val templated = buildPrompt(prompt, searchBlock)
        Log.d(TAG, "templated prompt >>> $templated <<<")

        val stops = listOf(
            "<|im_end|>", "<|mask_end|>", "<|eot_id|>", "<|end_of_text|>",
            "<|end|>", "<end_of_turn>", "</s>", "<|endoftext|>", "[/INST]",
            // Qwen sometimes starts a new turn after the first <|im_end|>;
            // cut there so the second turn never reaches the UI.
            "<|im_start|>"
        )

        try {
            var lastLen = 0
            val pending = StringBuilder()
            var lastFlush = System.currentTimeMillis()
            val flushMs = 33L
            var finished = false

            LlamaJni.nativeGenerateStreamingAdvanced(
                templated, maxTokens, temperature,
                /* topK = */ 50, /* topP = */ 0.9f, /* minP = */ 0.0f,
                /* repeatPenalty = */ 1.1f, /* penaltyLastN = */ 64,
                /* frequencyPenalty = */ 0.0f, /* presencePenalty = */ 0.0f, /* seed = */ 0,
                object : LlamaJni.StreamingCallback {
                    override fun onProgress(code: Int, text: String) {
                        if (finished || text.length <= lastLen) return
                        val delta = text.substring(lastLen); lastLen = text.length
                        var cut = delta.length
                        for (st in stops) { val i = delta.indexOf(st); if (i in 0 until cut) cut = i }
                        if (cut > 0) pending.append(delta, 0, cut)
                        val now = System.currentTimeMillis()
                        if (now - lastFlush >= flushMs || cut < delta.length) {
                            if (pending.isNotEmpty()) { trySend(pending.toString()); pending.setLength(0) }
                            lastFlush = now
                        }
                        if (cut < delta.length) finished = true
                    }
                })
            if (pending.isNotEmpty()) trySend(pending.toString())
            close()
        } catch (e: Throwable) { close(e) }
        awaitClose { }
    }

    fun stop() { try { LlamaJni.nativeAbortGeneration() } catch (_: Throwable) {} }
    fun unload() {
        try { 
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(200) }
            LlamaJni.nativeShutdown() 
        } catch (_: Throwable) {}
        loaded = false; cachedTemplate = null; fallbackName = ""
    }
}
