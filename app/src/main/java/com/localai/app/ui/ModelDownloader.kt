package com.localai.app.ui

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * One curated model available for download.
 * URLs point at HuggingFace "resolve/main" endpoints — direct download.
 */
data class DownloadableModel(
    val id: String,
    val displayName: String,
    val description: String,
    val sizeLabel: String,
    val fileName: String,
    val url: String,
    /** "gguf" (CPU / Hexagon) or "litertlm" (LiteRT). */
    val type: String = "gguf",
)

/**
 * Hardcoded catalog of GGUFs that fit comfortably on a modern phone.
 * Sizes are approximate — actual download is what matters.
 */
object ModelCatalog {

    /** CPU models (llama.cpp, any GGUF works). */
    val cpuModels = listOf(
        DownloadableModel(
            id = "llama-3.2-3b-q4km",
            displayName = "Llama 3.2 3B",
            description = "Fast, good balance. Recommended for first-time upgrades.",
            sizeLabel = "~2.0 GB",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf?download=true"
        ),
        DownloadableModel(
            id = "qwen-2.5-7b-q4km",
            displayName = "Qwen 2.5 7B",
            description = "Best quality that fits. Slower, more accurate.",
            sizeLabel = "~4.7 GB",
            fileName = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf?download=true"
        ),
        DownloadableModel(
            id = "mistral-7b-v03-q4km",
            displayName = "Mistral 7B v0.3",
            description = "Classic alternative to Qwen. Solid reasoning.",
            sizeLabel = "~4.4 GB",
            fileName = "Mistral-7B-Instruct-v0.3-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Mistral-7B-Instruct-v0.3-GGUF/resolve/main/Mistral-7B-Instruct-v0.3-Q4_K_M.gguf?download=true"
        )
    )

    /** NPU (Hexagon) — GGUF that runs fast on Qualcomm HTP via llama.cpp. */
    val npuHexagonModels = listOf(
        DownloadableModel(
            id = "qwen3.5-0.8b-q40",
            displayName = "Qwen3.5 0.8B",
            description = "Fastest Hexagon model. ~90 t/s on Snapdragon 8 Gen 3.",
            sizeLabel = "~365 MB",
            fileName = "Qwen3.5-0.8B-Q4_0.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_0.gguf?download=true",
            type = "gguf",
        ),
        DownloadableModel(
            id = "qwen3.5-2b-q40",
            displayName = "Qwen3.5 2B",
            description = "Good balance. Note: may fall back to CPU on Hexagon.",
            sizeLabel = "~1.2 GB",
            fileName = "Qwen3.5-2B-Q4_0.gguf",
            url = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_0.gguf?download=true",
            type = "gguf",
        ),
        DownloadableModel(
            id = "qwen2.5-7b-hex-q4km",
            displayName = "Qwen2.5 7B",
            description = "Best quality on Hexagon. ~14 t/s on Snapdragon 8 Gen 3.",
            sizeLabel = "~4.7 GB",
            fileName = "Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            url = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf?download=true",
            type = "gguf",
        ),
    )

    /** NPU (LiteRT) — .litertlm bundles for Google's official NPU runtime. */
    val npuLiteRtModels = listOf(
        DownloadableModel(
            id = "litert-qwen3-0.6b",
            displayName = "Qwen3 0.6B (LiteRT)",
            description = "Tiny, fast. Text-only. Ideal for testing LiteRT.",
            sizeLabel = "~586 MB",
            fileName = "Qwen3-0.6B.litertlm",
            url = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm?download=true",
            type = "litertlm",
        ),
        DownloadableModel(
            id = "litert-qwen2-vl-2b",
            displayName = "Qwen2-VL 2B (LiteRT)",
            description = "Vision model. Attach images and ask about them.",
            sizeLabel = "~1.78 GB",
            fileName = "Qwen2-VL-2B.litertlm",
            url = "https://huggingface.co/litert-community/Qwen2-VL-2B/resolve/main/Qwen2-VL-2B.litertlm?download=true",
            type = "litertlm",
        ),
        DownloadableModel(
            id = "litert-granite-4.0-350m",
            displayName = "Granite 4.0 350M (LiteRT)",
            description = "IBM's tiny model. Fast, English-focused.",
            sizeLabel = "~350 MB",
            fileName = "granite-4.0-350m_q8_ekv1280.litertlm",
            url = "https://huggingface.co/litert-community/granite-4.0-350m-litert-lm/resolve/main/granite-4.0-350m_q8_ekv1280.litertlm?download=true",
            type = "litertlm",
        ),
    )

    /** Legacy alias — everything, for any code that still expects one list. */
    val models: List<DownloadableModel> =
        cpuModels + npuHexagonModels + npuLiteRtModels
}

/**
 * State of a single download (by model id).
 */
data class DownloadState(
    val modelId: String,
    val progress: Float,        // 0..1
    val bytesRead: Long,
    val bytesTotal: Long,       // -1 if unknown
    val status: String,         // "Connecting…", "Downloading… 42%", "Done", "Failed: …"
    val isComplete: Boolean = false,
    val error: String? = null
)

/**
 * Downloads a GGUF from HuggingFace to the app's internal files dir.
 *
 *   - Streams to a `.gguf.part` temp file
 *   - Renames to `.gguf` on completion
 *   - Reports progress via the callback (called from a background thread)
 *   - Runs on the caller's coroutine — caller is responsible for Dispatchers.IO
 */
object ModelDownloader {

    private const val TAG = "ModelDownloader"
    private const val CHUNK = 1 shl 20        // 1 MB buffer
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 30_000

    /**
     * Downloads [model] into [targetDir]. Blocks until complete or failed.
     * Throws only on truly fatal conditions; ordinary network failures are
     * returned as an exception so the caller can surface them in state.
     *
     * @param onProgress called from the download thread with bytesRead/bytesTotal
     *                   (-1 for total if server didn't send Content-Length)
     */
    @Throws(Exception::class)
    fun download(
        model: DownloadableModel,
        targetDir: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (bytesRead: Long, bytesTotal: Long) -> Unit
    ): File {
        targetDir.mkdirs()
        val finalFile = File(targetDir, model.fileName)
        val partFile = File(targetDir, "${model.fileName}.part")

        // Already have it? Return it.
        if (finalFile.exists() && finalFile.length() > 0) {
            Log.i(TAG, "Already downloaded: ${finalFile.absolutePath}")
            return finalFile
        }

        // Clean any stale partial
        if (partFile.exists()) partFile.delete()

        Log.i(TAG, "Starting download: ${model.url}")
        val conn = (URL(model.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "LocalAI/1.0 (Android)")
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw Exception("HTTP $code ${conn.responseMessage ?: ""}")
            }

            val total = conn.contentLengthLong   // -1 if unknown
            onProgress(0L, total)

            var read = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    val buf = ByteArray(CHUNK)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        if (isCancelled()) {
                            throw java.util.concurrent.CancellationException("user cancelled")
                        }
                        output.write(buf, 0, n)
                        read += n
                        // Throttle progress callbacks to ~1 per 512 KB
                        if (read - lastReport >= 512 * 1024L) {
                            lastReport = read
                            onProgress(read, total)
                        }
                    }
                    output.flush()
                }
            }

            // Final progress tick
            onProgress(read, total)

            // Atomically rename
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                throw Exception("Could not rename .part to .gguf")
            }

            Log.i(TAG, "Download complete: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
            return finalFile
        } catch (t: Throwable) {
            // On any failure, delete the partial file
            try { partFile.delete() } catch (_: Throwable) {}
            throw t
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }
}
