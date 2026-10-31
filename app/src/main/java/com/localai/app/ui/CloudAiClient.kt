package com.localai.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for Cloudflare Workers AI image generation.
 * Supports:
 *   - Flux Schnell  (returns base64 in JSON)
 *   - SDXL Lightning / SDXL Base (returns raw PNG bytes)
 */
object CloudAiClient {
    private const val TAG = "CloudAiClient"

    const val MODEL_FLUX    = "@cf/black-forest-labs/flux-1-schnell"
    const val MODEL_SDXL_LT = "@cf/bytedance/stable-diffusion-xl-lightning"
    const val MODEL_SDXL    = "@cf/stabilityai/stable-diffusion-xl-base-1.0"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(330, TimeUnit.SECONDS)
        .build()

    /**
     * Generates an image. Blocks until the API responds. Caller must run on IO.
     * Throws on network error, auth error, or if Cloudflare returns a non-image.
     */
    suspend fun generate(
        accountId: String,
        apiToken: String,
        prompt: String,
        model: String = MODEL_FLUX,
        steps: Int = 4,
    ): Bitmap = withContext(Dispatchers.IO) {
        require(accountId.isNotBlank()) { "Cloudflare Account ID not set" }
        require(apiToken.isNotBlank())  { "Cloudflare API Token not set" }
        require(prompt.isNotBlank())    { "Prompt is empty" }

        val url = "https://api.cloudflare.com/client/v4/accounts/" +
                  "$accountId/ai/run/$model"

        // Flux Schnell rejects num_steps (only takes prompt).
        // SDXL variants accept num_steps.
        val json = JSONObject().put("prompt", prompt)
        if (model != MODEL_FLUX) {
            json.put("num_steps", steps)
        }
        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiToken")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        Log.i(TAG, "POST $url prompt=${prompt.take(60)}… steps=$steps")

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string() ?: ""
                Log.e(TAG, "HTTP ${resp.code}: $err")
                throw RuntimeException("HTTP ${resp.code}: ${err.take(200)}")
            }

            val contentType = resp.header("Content-Type") ?: ""
            val bytes = resp.body?.bytes()
                ?: throw RuntimeException("Empty response body")

            // Case 1: raw PNG (SDXL)
            if (contentType.startsWith("image/") ||
                (bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte())) {
                return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw RuntimeException("Failed to decode PNG")
            }

            // Case 2: JSON with base64 "image" field (Flux)
            val text = bytes.toString(Charsets.UTF_8)
            val json = JSONObject(text)

            if (!json.optBoolean("success", true)) {
                val errs = json.optJSONArray("errors")?.toString() ?: text
                throw RuntimeException("Cloudflare error: ${errs.take(200)}")
            }

            val b64 = json.optJSONObject("result")?.optString("image")
                ?: throw RuntimeException("No image field in response")

            val png = Base64.decode(b64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(png, 0, png.size)
                ?: throw RuntimeException("Failed to decode base64 image")
        }
    }
}
