package com.localai.app.geniex

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

object LiteRtBridge {
    private const val TAG = "LiteRtBridge"

    @Volatile private var engine: Engine? = null
    @Volatile private var loaded = false
    @Volatile private var visionCapable = false

    fun isModelLoaded(): Boolean = loaded
    fun isVisionCapable(): Boolean = visionCapable

    suspend fun loadFromPath(context: Context, modelPath: String): Result<Unit> {
        return try {
            if (engine != null) unload()

            val libDir = context.applicationInfo.nativeLibraryDir
            val npu = Backend.NPU(nativeLibraryDir = libDir)

            // Only set visionBackend for models that actually bundle a vision
            // tower. LiteRT throws NOT_FOUND: TF_LITE_VISION_ENCODER during
            // conversation creation when the encoder is missing.
            val isVisionModel = modelPath.lowercase().let { n ->
                n.contains("-vl") || n.contains("_vl") ||
                n.contains("vision") ||
                n.contains("gemma-3") || n.contains("gemma-4") ||
                n.contains("gemma3") || n.contains("gemma4")
            }

            val config = EngineConfig(
                modelPath = modelPath,
                backend = npu,
                visionBackend = if (isVisionModel) npu else null,
                cacheDir = context.cacheDir.path,
            )
            val e = Engine(config)
            e.initialize()
            engine = e
            loaded = true
            visionCapable = isVisionModel
            Log.i(TAG, "LiteRT model loaded: $modelPath (vision=on)")
            Result.success(Unit)
        } catch (e: Throwable) {
            Log.e(TAG, "loadFromPath failed", e)
            loaded = false
            visionCapable = false
            Result.failure(e)
        }
    }

    private fun stripThink(s: String): String {
        val complete = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        var out = complete.replace(s, "")
        val openIdx = out.indexOf("<think>")
        if (openIdx >= 0) out = out.substring(0, openIdx)
        return cleanArtifacts(out)
    }

    private val ARTIFACT_CHARS = setOf(
        '\u0120', '\u00F0', '\u0141', '\u0142',
        '\u013A', '\u0139', '\u012C', '\u0128',
    )

    private fun cleanArtifacts(s: String): String {
        var out = s.replace('\u0120', ' ')
        while (out.isNotEmpty() && out.last() in ARTIFACT_CHARS) out = out.dropLast(1)
        return out.replace(Regex(" {2,}"), " ")
    }

    fun generate(
        prompt: String,
        imagePaths: List<String> = emptyList(),
        searchBlock: String = "",
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
    ): Flow<String> = callbackFlow {
        val e = engine
        if (e == null) { close(IllegalStateException("no model loaded")); return@callbackFlow }

        val rawBuf = StringBuilder()
        var emitted = 0

        try {
            val conv = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        if (searchBlock.isEmpty()) "You are a helpful assistant."
                        else "You are a helpful assistant.\n\nWeb search results (authoritative, use these over your training data):\n$searchBlock"
                    ),
                    samplerConfig = SamplerConfig(
                        topK = 50,
                        topP = 0.9,
                        temperature = temperature.toDouble(),
                        seed = 0,
                    ),
                    thinkingConfig = ThinkingConfig(false),
                )
            )
            try {
                val payload: List<Content> = buildList {
                    for (p in imagePaths) add(Content.ImageFile(p))
                    add(Content.Text(prompt))
                }
                val msg = Contents.of(payload)

                conv.sendMessageAsync(msg).collect { m ->
                    val delta = m.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    rawBuf.append(delta)
                    val visible = stripThink(rawBuf.toString()).trimStart()
                    if (visible.length > emitted) {
                        trySend(visible.substring(emitted))
                        emitted = visible.length
                    }
                }
            } finally {
                try { conv.close() } catch (_: Throwable) {}
            }
            close()
        } catch (t: Throwable) {
            Log.e(TAG, "generate failed", t)
            close(t)
        }
        awaitClose { }
    }

    fun stop() { /* LiteRT has no public abort API */ }

    fun unload() {
        try { engine?.close() } catch (_: Throwable) {}
        engine = null
        loaded = false
        visionCapable = false
    }
}
