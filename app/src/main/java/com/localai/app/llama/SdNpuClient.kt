package com.localai.app.llama

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File
import java.io.FileWriter
import java.util.concurrent.TimeUnit

object SdNpuClient {
    private const val TAG = "SdNpuClient"
    private const val HOST = "127.0.0.1:8081"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS) // 5 min timeout instead of infinite
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val healthClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(200, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build()
    }

    data class Progress(val step: Int, val total: Int, val previewBmp: Bitmap?)
    data class Result(val bitmap: Bitmap, val seed: Long, val generationTimeMs: Long)

    // Debug log file the user can `cat` from Termux
    private fun dlog(msg: String) {
        try {
            val f = File("/sdcard/Download/LocalAI-App/sd-npu-client.log")
            f.parentFile?.mkdirs()
            FileWriter(f, true).use { it.append("${System.currentTimeMillis()}: $msg\n") }
        } catch (_: Throwable) {}
        Log.i(TAG, msg)
    }

    suspend fun isHealthy(): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("http://$HOST/health").get().build()
            healthClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Throwable) { false }
    }

    /** Decode either a JPEG/PNG or a raw RGB byte buffer into a Bitmap. */
    private fun decodeImage(bytes: ByteArray, format: String, w: Int, h: Int): Bitmap? {
        return when (format.lowercase()) {
            "raw" -> {
                if (bytes.size < w * h * 3) {
                    dlog("raw too small: ${bytes.size} < ${w*h*3}")
                    return null
                }
                val pixels = IntArray(w * h)
                var p = 0
                for (i in 0 until w * h) {
                    val r = bytes[p].toInt() and 0xFF
                    val g = bytes[p+1].toInt() and 0xFF
                    val b = bytes[p+2].toInt() and 0xFF
                    p += 3
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            }
            "rgba" -> {
                val pixels = IntArray(w * h)
                var p = 0
                for (i in 0 until w * h) {
                    val r = bytes[p].toInt() and 0xFF
                    val g = bytes[p+1].toInt() and 0xFF
                    val b = bytes[p+2].toInt() and 0xFF
                    val a = bytes[p+3].toInt() and 0xFF
                    p += 4
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            }
            else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    suspend fun waitForBackend(timeoutMs: Long = 60000): Boolean = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val req = Request.Builder().url("http://$HOST/health").build()
                healthClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 404) return@withContext true
                }
            } catch (_: Exception) {}
            kotlinx.coroutines.delay(500)
        }
        false
    }

    suspend fun generate(
        prompt: String,
        negativePrompt: String,
        steps: Int,
        cfg: Float,
        seed: Long?,
        width: Int,
        height: Int,
        denoiseStrength: Float = 0.6f,
        scheduler: String = "dpm",
        onProgress: (Progress) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        dlog("=== generate start ===")
        dlog("prompt=$prompt steps=$steps cfg=$cfg size=${width}x$height sched=$scheduler")

        val body = JSONObject().apply {
            put("prompt", prompt)
            put("negative_prompt", negativePrompt)
            put("steps", steps)
            put("cfg", cfg.toDouble())
            put("seed", seed ?: -1L)
            put("width", width)
            put("height", height)
            put("denoise_strength", denoiseStrength.toDouble())
            put("scheduler", scheduler)
            put("preview_format", "jpeg")
            put("show_diffusion_process", true)
            put("show_diffusion_stride", 1)
            put("aspect_ratio", "1:1")
        }.toString()
        dlog("request=$body")

        val req = Request.Builder()
            .url("http://$HOST/generate")
            .post(body.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        var finalBmp: Bitmap? = null
        var finalSeed: Long = -1L
        var finalGenMs: Long = 0L

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                dlog("HTTP ${resp.code}: ${resp.message}")
                throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
            }
            val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream()))
            while (true) {
                val line = reader.readLine() ?: break
                if (!line.startsWith("data: ")) continue
                val payload = line.substring(6).trim()
                if (payload == "[DONE]") break

                val msg = try { JSONObject(payload) } catch (_: Throwable) { continue }
                val type = msg.optString("type")
                dlog("event: $type")

                when (type) {
                    "progress" -> {
                        val step = msg.optInt("step")
                        val total = msg.optInt("total_steps").coerceAtLeast(1)
                        val b64 = msg.optString("image", "")
                        val fmt = msg.optString("format", "raw")
                        var bmp: Bitmap? = null
                        if (b64.isNotEmpty()) {
                            try {
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                bmp = decodeImage(bytes, fmt, width, height)
                            } catch (t: Throwable) { dlog("preview decode failed: ${t.message}") }
                        }
                        onProgress(Progress(step, total, bmp))
                    }
                    "complete" -> {
                        val b64 = msg.optString("image", "")
                        val fmt = msg.optString("format", "raw")
                        dlog("complete: format=$fmt b64len=${b64.length} w=${msg.optInt("width", -1)} h=${msg.optInt("height", -1)}")
                        if (b64.isNotEmpty()) {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            dlog("complete: decoded ${bytes.size} bytes")
                            val realW = msg.optInt("width", width)
                            val realH = msg.optInt("height", height)
                            finalBmp = decodeImage(bytes, fmt, realW, realH)
                        }
                        finalSeed = msg.optLong("seed", -1L)
                        finalGenMs = msg.optLong("generation_time_ms", 0L)
                    }
                    "error" -> {
                        dlog("error: ${msg.optString("message", "?")}")
                        throw RuntimeException(msg.optString("message", "backend error"))
                    }
                }
            }
        }
        val bmp = finalBmp ?: throw RuntimeException("backend returned no image")
        Result(bmp, finalSeed, finalGenMs)
    }
}
