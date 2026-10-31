package com.localai.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localai.app.data.UserPreferencesRepository
import com.localai.app.llama.StableDiffusionBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject

data class ImageDownloadState(
    val modelId: String,
    val progress: Float = 0f,
    val bytesRead: Long = 0L,
    val bytesTotal: Long = -1L,
    val status: String = "Connecting…",
    val isComplete: Boolean = false,
    val error: String? = null,
)

data class ImageState(
    val sdModelLoaded: Boolean = false,
    val sdModelName: String? = null,
    val loraName: String? = null,
    val prompt: String = "",
    val negativePrompt: String = "ugly, blurry, low quality, distorted, deformed, red tint, oversaturated, magenta, watermark, text",
    val steps: Int = 6,
    val cfg: Float = 1.0f,
    val width: Int = 512,
    val height: Int = 512,
    val seed: Int = -1,
    val loraScale: Float = 0.9f,
    val isGenerating: Boolean = false,
    val status: String = "",
    val progress: Float = -1f,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val elapsedSeconds: Int = 0,
    val image: Bitmap? = null,
    val error: String? = null,
    val scheduler: String = "LCM",
    val karras: Boolean = false,
    val aspectRatio: String = "1:1",
    val batchCount: Int = 1,
    val denoiseStrength: Float = 1.0f,
    val importProgress: Float = -1f,
    val importStatus: String = "",
    val downloads: Map<String, ImageDownloadState> = emptyMap()
)

@HiltViewModel
class ImageViewModel @Inject constructor(
    application: Application,
    private val prefs: UserPreferencesRepository
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ImageState())

    // Debug log to a file Termux can read
    private fun ilog(msg: String) {
        Log.i("ImageVM", msg)
        try {
            val f = java.io.File("/sdcard/Download/LocalAI-App/sd-npu-import.log")
            f.parentFile?.mkdirs()
            java.io.FileWriter(f, true).use { it.append("${System.currentTimeMillis()}: $msg\n") }
        } catch (_: Throwable) {}
    }

    val state: StateFlow<ImageState> = _state.asStateFlow()

    // ============================================================
    // NPU image generation
    // ============================================================

    /** Starts the SD-NPU foreground service for the given model directory. */
    fun startNpuService(modelDir: String, backendOverride: String? = null) {
        lastStartRequestTime = System.currentTimeMillis()
        val ctx = getApplication<Application>()
        val backend = backendOverride ?: prefs(ctx).getString("npu_backend_type", "sd15npu") ?: "sd15npu"
        val intent = android.content.Intent(ctx, com.localai.app.service.SdNpuService::class.java).apply {
            action = com.localai.app.service.SdNpuService.ACTION_START
            putExtra(com.localai.app.service.SdNpuService.EXTRA_MODEL_DIR, modelDir)
            putExtra(com.localai.app.service.SdNpuService.EXTRA_BACKEND, backend)
            putExtra(com.localai.app.service.SdNpuService.EXTRA_LOWRAM, true)
            putExtra(com.localai.app.service.SdNpuService.EXTRA_WIDTH, _state.value.width)
            putExtra(com.localai.app.service.SdNpuService.EXTRA_HEIGHT, _state.value.height)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent)
        } else {
            ctx.startService(intent)
        }
        _state.value = _state.value.copy(status = "Starting Image Backend ($backend)…")
    }

    fun deleteNpuModel() {
        val ctx = getApplication<Application>()
        val prefs = prefs(ctx)
        val npuDirStr = prefs.getString("npu_model_dir", "") ?: ""

        if (npuDirStr.isNotEmpty()) {
            // 1. Stop the backend synchronously so it releases file locks
            stopNpuServiceSync()
            
            // 2. Delete the actual model directory
            try {
                // Give the OS a moment to release memory-mapped file handles
                Thread.sleep(500)
                val dir = java.io.File(npuDirStr)
                if (dir.exists()) {
                    val deleted = dir.deleteRecursively()
                    Log.i("ImageVM", "Deleted NPU dir: $deleted")
                }
            } catch (e: Throwable) {
                Log.e("ImageVM", "Failed to delete NPU model dir", e)
            }

            // 3. Clear preferences
            prefs.edit()
                .remove("npu_model_dir")
                .remove("npu_backend_type")
                .remove("npu_started_dir")
                .apply()

            // 4. Clear UI state
            _state.value = _state.value.copy(
                status = "NPU model deleted",
                image = null
            )
        }
    }

    fun stopNpuService() {
        val ctx = getApplication<Application>()
        val intent = android.content.Intent(ctx, com.localai.app.service.SdNpuService::class.java).apply {
            action = com.localai.app.service.SdNpuService.ACTION_STOP
        }
        ctx.startService(intent)
        
        _state.value = _state.value.copy(
            status = "NPU backend stopped",
            isGenerating = false
        )
    }

    private val imageDownloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private var generationJob: kotlinx.coroutines.Job? = null

    fun cancelGeneration() {
        generationJob?.cancel()
        generationJob = null
        _state.value = _state.value.copy(
            isGenerating = false,
            status = "Cancelled",
            error = null,
            progress = -1f
        )
    }
    private val cancelledImageDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var lastStartRequestTime = 0L

    /** Downloads an image model's ZIP, then hands it to importNpuModel(). */
    fun downloadImageModel(model: ImageModel) {
        val ctx = getApplication<Application>()
        
        try {
            val request = android.app.DownloadManager.Request(android.net.Uri.parse(model.url))
                .setTitle(model.displayName)
                .setDescription("Downloading AI Model (${model.sizeLabel})...")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    android.os.Environment.DIRECTORY_DOWNLOADS,
                    "AI-Models/${model.fileName}"
                )
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            val downloadManager = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val downloadId = downloadManager.enqueue(request)

            // Update UI to show it's downloading
            _state.value = _state.value.copy(
                downloads = _state.value.downloads + (model.id to ImageDownloadState(
                    modelId = model.id,
                    status = "Downloading...",
                    progress = 0f
                ))
            )
            
            // Poll the DownloadManager for actual progress
            viewModelScope.launch {
                var downloading = true
                while (downloading) {
                    kotlinx.coroutines.delay(1000) // Check every second
                    val cursor = downloadManager.query(android.app.DownloadManager.Query().setFilterById(downloadId))
                    
                    if (cursor != null && cursor.moveToFirst()) {
                        val status = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                        val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                        if (status == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                            downloading = false
                            _state.value = _state.value.copy(
                                downloads = _state.value.downloads + (model.id to ImageDownloadState(
                                    modelId = model.id, progress = 1f, status = "Installed", isComplete = true
                                ))
                            )
                        } else if (status == android.app.DownloadManager.STATUS_FAILED) {
                            downloading = false
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON))
                            _state.value = _state.value.copy(
                                downloads = _state.value.downloads + (model.id to ImageDownloadState(
                                    modelId = model.id, error = "Download failed (code $reason)"
                                ))
                            )
                        } else {
                            val prog = if (bytesTotal > 0) bytesDownloaded.toFloat() / bytesTotal else 0f
                            val statusText = if (bytesTotal > 0) "Downloading… ${(prog * 100).toInt()}%" else "Downloading… ${bytesDownloaded / 1048576} MB"
                            
                            _state.value = _state.value.copy(
                                downloads = _state.value.downloads + (model.id to ImageDownloadState(
                                    modelId = model.id, progress = prog, status = statusText
                                ))
                            )
                        }
                        cursor.close()
                    }
                }
            }
        } catch (e: Throwable) {
            ilog("DownloadManager failed: ${e.message}")
            _state.value = _state.value.copy(
                downloads = _state.value.downloads + (model.id to ImageDownloadState(
                    modelId = model.id, error = "Failed to start download"
                ))
            )
        }
    }

    fun cancelImageDownload(modelId: String) {
        cancelledImageDownloads.add(modelId)
        imageDownloadJobs[modelId]?.cancel()
        imageDownloadJobs.remove(modelId)
        _state.value = _state.value.copy(
            downloads = _state.value.downloads - modelId
        )
    }

    /**
     * Extracts a user-picked ZIP into filesDir/npu-models/<name>/ (top-level
     * files, matching Local Dream). Detects SDXL vs SD1.5 from the extracted
     * file set and saves both the path and the detected backend type to prefs.
     *
     * Accepts both content:// (SAF) and file:// (internal download) URIs.
     */
    fun importNpuModel(uri: android.net.Uri, onDone: (String?, String) -> Unit) {
        ilog("importNpuModel start uri=$uri scheme=${uri.scheme}")
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val rawName = uri.lastPathSegment?.substringAfterLast('/')
                ?.removeSuffix(".zip") ?: "npu-model-${System.currentTimeMillis()}"
            val name = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")

            _state.value = _state.value.copy(
                status = "Copying ZIP…",
                importProgress = 0f,
                importStatus = "Copying ZIP…"
            )

            val result = withContext(Dispatchers.IO) {
                var destDir: File? = null
                val tmpZip = File(ctx.cacheDir, "import-$name.zip")
                try {
                    // ---- Step 1: copy SAF URI to local cache with progress ----
                    val totalBytes = try {
                        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                    } catch (_: Throwable) { -1L }
                    ilog("zip total=$totalBytes")

                    var copied = 0L
                    val srcStream: java.io.InputStream? = if (uri.scheme == "file") {
                        uri.path?.let { java.io.File(it).takeIf { f -> f.exists() }?.inputStream() }
                    } else {
                        ctx.contentResolver.openInputStream(uri)
                    }
                    srcStream?.use { input ->
                        FileOutputStream(tmpZip).use { out ->
                            val buf = ByteArray(1 shl 20)
                            var n: Int
                            var lastPct = -1
                            while (input.read(buf).also { n = it } > 0) {
                                out.write(buf, 0, n)
                                copied += n
                                if (totalBytes > 0) {
                                    val pct = ((copied * 100) / totalBytes).toInt()
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        _state.value = _state.value.copy(
                                            importProgress = copied.toFloat() / totalBytes,
                                            importStatus = "Copying ZIP… $pct%",
                                            status = "Copying ZIP… $pct%"
                                        )
                                    }
                                }
                            }
                        }
                    } ?: throw java.io.IOException("openInputStream returned null")
                    ilog("copied $copied bytes to $tmpZip")

                    // ---- Step 2: extract from the local zip file ----
                    _state.value = _state.value.copy(
                        importProgress = 0f,
                        importStatus = "Extracting…",
                        status = "Extracting…"
                    )

                        // Extract to internal app storage to bypass Scoped Storage
                        val modelsDir = File(ctx.filesDir, "npu-models")
                        modelsDir.mkdirs()
                        destDir = File(modelsDir, name).apply {
                            if (exists()) deleteRecursively()
                            mkdirs()
                        }

                    var fileCount = 0
                    var totalExtracted = 0L
                    // Rough total from zip entry sizes for progress
                    var expectedTotal = 0L
                    java.util.zip.ZipFile(tmpZip).use { zf ->
                        zf.entries().asSequence().forEach { e ->
                            if (!e.isDirectory) expectedTotal += e.size
                        }
                    }
                    ilog("expected uncompressed=$expectedTotal")

                    java.util.zip.ZipFile(tmpZip).use { zf ->
                        val entries = zf.entries()
                        while (entries.hasMoreElements()) {
                            val e = entries.nextElement()
                            val baseName = e.name.substringAfterLast('/')
                            if (baseName.isEmpty() || e.isDirectory) continue
                            val f = File(destDir, baseName)
                            zf.getInputStream(e).use { input ->
                                FileOutputStream(f).use { out ->
                                    val buf = ByteArray(1 shl 20)
                                    var n: Int
                                    while (input.read(buf).also { n = it } > 0) {
                                        out.write(buf, 0, n)
                                        totalExtracted += n
                                    }
                                }
                            }
                            fileCount++
                            val prog = if (expectedTotal > 0)
                                (totalExtracted.toFloat() / expectedTotal).coerceIn(0f, 1f)
                            else 0f
                            _state.value = _state.value.copy(
                                importProgress = prog,
                                importStatus = "Extracting ${e.name.substringAfterLast('/')} ($fileCount)",
                                status = "Extracting ($fileCount files, ${totalExtracted / (1 shl 20)} MB)"
                            )
                            ilog("extracted $baseName ($totalExtracted / $expectedTotal)")
                        }
                    }
                    ilog("extract done: $fileCount files, $totalExtracted bytes")

                    val hasClipMnn   = File(destDir, "clip.mnn").exists()
                    val hasClip2Mnn  = File(destDir, "clip_2.mnn").exists()
                    val hasClipV2    = File(destDir, "clip_v2.mnn").exists()
                    val backend = when {
                        hasClipMnn || hasClip2Mnn -> "sdxl"
                        hasClipV2                 -> "sd15npu"
                        else                      -> "sdxl"
                    }
                    ilog("backend=$backend clip=$hasClipMnn clip2=$hasClip2Mnn clipv2=$hasClipV2")

                    // ---- Read config.json (DMD2 models ship step/cfg/scheduler) ----
                    val cfgFile = File(destDir, "config.json")
                    if (cfgFile.exists()) {
                        val txt = cfgFile.readText()
                        ilog("config.json: $txt")
                        try {
                            val j = org.json.JSONObject(txt)
                            // Try common key names
                            val steps = j.optInt("steps",
                                        j.optInt("num_inference_steps",
                                        j.optInt("num_steps",
                                        j.optInt("default_steps", -1))))
                            val cfg = j.optDouble("cfg",
                                        j.optDouble("guidance_scale",
                                        j.optDouble("cfg_scale",
                                        j.optDouble("default_cfg", -1.0))))
                            val sched = j.optString("scheduler",
                                          j.optString("sampler",
                                          j.optString("default_scheduler", "")))
                            val denoise = j.optDouble("denoise_strength",
                                            j.optDouble("strength", -1.0))
                            ilog("parsed: steps=$steps cfg=$cfg sched=$sched denoise=$denoise")

                            val pref = prefs(ctx)
                            pref.edit().apply {
                                // Clear old keys first so stale values don't override
                                remove("npu_cfg_steps")
                                remove("npu_cfg_cfg")
                                remove("npu_cfg_scheduler")
                                remove("npu_cfg_denoise")
                                if (steps > 0) putInt("npu_cfg_steps", steps)
                                if (cfg > 0) putFloat("npu_cfg_cfg", cfg.toFloat())
                                if (sched.isNotEmpty()) putString("npu_cfg_scheduler", sched)
                                if (denoise > 0) putFloat("npu_cfg_denoise", denoise.toFloat())
                            }.apply()

                            // Also sync the UI sliders to match
                            val uiSt = _state.value
                            _state.value = uiSt.copy(
                                steps = if (steps > 0) steps else uiSt.steps,
                                cfg = if (cfg > 0) cfg.toFloat() else uiSt.cfg
                            )
                        } catch (t: Throwable) {
                            ilog("config.json parse failed: ${t.message}")
                        }
                    } else {
                        ilog("no config.json in zip")
                    }

                    Pair(destDir.absolutePath, backend)
                } catch (t: Throwable) {
                    ilog("EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
                    t.stackTrace.take(8).forEach { ilog("  at $it") }
                    destDir?.deleteRecursively()
                    null
                } finally {
                    try { tmpZip.delete() } catch (_: Throwable) {}
                }
            }

            if (result != null) {
                val (path, backend) = result
                prefs(ctx).edit()
                    .putString("npu_model_dir", path)
                    .putString("npu_backend_type", backend)
                    .apply()
                _state.value = _state.value.copy(
                    status = "Imported ($backend): ${File(path).name}",
                    importProgress = -1f,
                    importStatus = ""
                )
                ilog("import success: $path ($backend)")
                onDone(path, backend)
            } else {
                _state.value = _state.value.copy(
                    status = "Import failed — see sd-npu-import.log",
                    importProgress = -1f,
                    importStatus = ""
                )
                ilog("import failed")
                onDone(null, "")
            }
        }
    }

    fun generateNpu() {
        if (_state.value.isGenerating) return
        val ctx = getApplication<Application>()
        val npuDir = prefs(ctx).getString("npu_model_dir", "") ?: ""
        if (npuDir.isEmpty()) {
            _state.value = _state.value.copy(error = "Select a model first")
            return
        }

        generationJob = viewModelScope.launch {
            _state.value = _state.value.copy(isGenerating = true, status = "Starting backend...", error = null)

            if (!com.localai.app.service.SdNpuService.isRunning) {
                val isNpu = prefs(ctx).getBoolean("use_npu_image", false)
                var backend = prefs(ctx).getString("npu_backend_type", "sd15npu") ?: "sd15npu"
                if (!isNpu && backend == "sd15npu") {
                    backend = "sd15cpu"
                }
                startNpuService(npuDir, backend)
            }
            
            // JUST FIRE IT. The HTTP client has a 5-minute timeout and will wait for the backend.
            doGenerateNpu()
        }
    }

    private fun stopNpuServiceSync() {
        try {
            val ctx = getApplication<Application>()
            val intent = android.content.Intent(ctx, com.localai.app.service.SdNpuService::class.java).apply {
                action = com.localai.app.service.SdNpuService.ACTION_STOP
            }
            ctx.startService(intent)
            // Give the service a moment to actually tear down the native process
            Thread.sleep(300)
        } catch (_: Throwable) {}
    }

    private fun doGenerateNpu() {
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(isGenerating = true, status = "Generating on NPU…",
                                  progress = 0f, currentStep = 0, totalSteps = s.steps,
                                  error = null)
            try {
                val result = com.localai.app.llama.SdNpuClient.generate(
                prompt = s.prompt,
                negativePrompt = s.negativePrompt,
                steps = prefs(getApplication()).getInt("npu_cfg_steps", s.steps),
                cfg = prefs(getApplication()).getFloat("npu_cfg_cfg", s.cfg),
                seed = if (s.seed < 0) null else s.seed.toLong(),
                width = s.width,
                height = s.height,
                denoiseStrength = prefs(getApplication())
                    .getFloat("npu_cfg_denoise", 1.0f),
                scheduler = run {
                        val pref = prefs(getApplication())
                        val ui = pref.getString(Prefs.NPU_UI_SCHEDULER, "") ?: ""
                        val karras = pref.getBoolean(Prefs.NPU_UI_KARRAS, false)
                        val cfgSched = pref.getString("npu_cfg_scheduler", "") ?: ""
                        val modelDir = pref.getString("npu_model_dir", "") ?: ""
                        val modelName = modelDir.substringAfterLast('/').lowercase()
                        val isDmd = modelName.contains("dmd") ||
                                    modelName.contains("lcm") ||
                                    modelName.contains("turbo")
                        when {
                            // 1. User picked explicitly in dialog
                            ui.isNotEmpty() ->
                                if (karras && !ui.contains("karras")) "$ui-karras" else ui
                            // 2. config.json from the imported zip
                            cfgSched.isNotEmpty() -> cfgSched
                            // 3. Model-name heuristic
                            isDmd -> "lcm"
                            // 4. Hard default
                            else -> "euler_a"
                        }
                    },
                    onProgress = { p ->
                        _state.value = _state.value.copy(
                            currentStep = p.step,
                            totalSteps = p.total,
                            progress = p.step.toFloat() / p.total.coerceAtLeast(1),
                            image = p.previewBmp ?: _state.value.image
                        )
                    }
                )
                _state.value = _state.value.copy(
                    isGenerating = false,
                    image = result.bitmap,
                    progress = 1f,
                    seed = result.seed.toInt(),
                    status = "Done (%.1fs)".format(result.generationTimeMs / 1000.0)
                )
            } catch (t: Throwable) {
                Log.e("ImageVM", "NPU generate failed", t)
                _state.value = _state.value.copy(
                    isGenerating = false,
                    status = "",
                    error = "NPU generation failed: ${t.message}"
                )
            }
        }
    }


    init {
        // Seed the initial state from user Preferences so the Settings-tab
        // sliders affect new sessions. In-tab sliders still override live.
        try {
            val p = prefs(getApplication())
            _state.value = _state.value.copy(
                steps = p.getInt(Prefs.STEPS, _state.value.steps),
                cfg = p.getFloat(Prefs.CFG, _state.value.cfg),
                width = p.getInt(Prefs.WIDTH, _state.value.width),
                height = p.getInt(Prefs.HEIGHT, _state.value.height)
            )
        } catch (_: Exception) {}

        // Auto-restore previously loaded SD model + LoRA (if files still exist)
        viewModelScope.launch {
            try {
                val savedSd = prefs.sdModelNameFlow.firstOrNull() ?: ""
                if (savedSd.isNotEmpty()) {
                    val file = File(getApplication<Application>().filesDir, savedSd)
                    if (file.exists() && file.length() > 0) {
                        Log.i("ImageVM", "Auto-restoring SD model: $savedSd")
                        loadSDFromFile(file)
                    } else {
                        prefs.clearSdModelName()
                    }
                }

                val savedLora = prefs.loraNameFlow.firstOrNull() ?: ""
                if (savedLora.isNotEmpty()) {
                    val file = File(getApplication<Application>().filesDir, savedLora)
                    if (file.exists() && file.length() > 0) {
                        Log.i("ImageVM", "Auto-restoring LoRA: $savedLora")
                        _state.value = _state.value.copy(loraName = savedLora)
                    } else {
                        prefs.clearLoraName()
                    }
                }
            } catch (e: Exception) {
                Log.e("ImageVM", "Auto-restore failed", e)
            }
        }
    }

    // ---------- Model loading ----------

    fun loadSDModel(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, status = "Copying SD model…", progress = 0f)
            val ctx = getApplication<Application>()
            val name = queryName(uri) ?: "sd-model.gguf"
            val existing = File(ctx.filesDir, name)
            if (existing.exists() && existing.length() > 0) {
                loadSDFromFile(existing)
                return@launch
            }
            val copied = copyWithProgress(uri, existing)
            if (copied == null) {
                _state.value = _state.value.copy(error = "Failed to copy SD model", progress = -1f)
                return@launch
            }
            loadSDFromFile(copied)
        }
    }

    fun loadLoRA(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, status = "Copying LoRA…", progress = 0f)
            val ctx = getApplication<Application>()
            val name = queryName(uri) ?: "lora.safetensors"
            val existing = File(ctx.filesDir, name)
            if (existing.exists() && existing.length() > 0) {
                try { prefs.saveLoraName(name) } catch (_: Exception) {}
                _state.value = _state.value.copy(loraName = name, status = "", progress = -1f)
                return@launch
            }
            val copied = copyWithProgress(uri, existing)
            if (copied == null) {
                _state.value = _state.value.copy(error = "Failed to copy LoRA", progress = -1f)
                return@launch
            }
            try { prefs.saveLoraName(name) } catch (_: Exception) {}
            _state.value = _state.value.copy(loraName = name, status = "", progress = -1f)
        }
    }

    fun loadInstalledSDModel(file: File) {
        if (file.isDirectory) {
            // Handle MNN model folder
            prefs(getApplication()).edit().putString("npu_model_dir", file.absolutePath).apply()
            _state.value = _state.value.copy(
                sdModelName = file.name,
                sdModelLoaded = true
            )
            return
        }

        viewModelScope.launch { loadSDFromFile(file) }
    }

    /** Deletes an installed model file from internal storage. */
    fun deleteInstalledFile(file: File) {
        viewModelScope.launch {
            try {
                if (_state.value.sdModelName == file.name) {
                    StableDiffusionBridge.freeSDModel()
                    prefs.clearSdModelName()
                    _state.value = _state.value.copy(sdModelLoaded = false, sdModelName = null)
                }
                if (_state.value.loraName == file.name) {
                    prefs.clearLoraName()
                    _state.value = _state.value.copy(loraName = null)
                }
                withContext(Dispatchers.IO) { file.delete() }
            } catch (e: Exception) {
                Log.e("ImageVM", "delete failed", e)
            }
        }
    }

    fun loadInstalledLoRA(file: File) {
        viewModelScope.launch {
            try { prefs.saveLoraName(file.name) } catch (_: Exception) {}
            _state.value = _state.value.copy(loraName = file.name)
        }
    }

    fun listInstalledSDModels(): List<File> {
        val ctx = getApplication<Application>()
        // 1. Standalone model files (like safetensors or gguf)
        val standaloneFiles = ctx.filesDir.listFiles { f -> 
            f.isFile && (f.name.endsWith(".safetensors", true) || f.name.endsWith(".ckpt", true) || f.name.endsWith(".gguf", true)) 
        }?.toMutableList() ?: mutableListOf()
        
        // 2. Downloaded model folders (MNN or QNN)
        val modelDirs = File(android.os.Environment.getExternalStorageDirectory(), "Download/AI-Models").listFiles { f -> 
            f.isDirectory 
        }?.toMutableList() ?: mutableListOf()
        
        standaloneFiles.addAll(modelDirs)
        return standaloneFiles.sortedByDescending { it.lastModified() }
    }

    fun listInstalledLoRAs(): List<File> =
        getApplication<Application>().filesDir
            .listFiles { f -> f.isFile && f.name.endsWith(".safetensors", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    // ---------- Parameter setters ----------

    fun setPrompt(v: String)              { _state.value = _state.value.copy(prompt = v) }
    fun setNegative(v: String)            { _state.value = _state.value.copy(negativePrompt = v) }
    fun setSteps(v: Int)                  { _state.value = _state.value.copy(steps = v) }
    fun setCfg(v: Float)                  { _state.value = _state.value.copy(cfg = v) }
    fun setWidth(v: Int)                  { _state.value = _state.value.copy(width = v) }
    fun setHeight(v: Int)                 { _state.value = _state.value.copy(height = v) }
    fun applyAdvanced(
        aspect: String, scheduler: String, karras: Boolean,
        steps: Int, cfg: Float, batch: Int, denoise: Float, seed: String
    ) {
        val ctx = getApplication<Application>()
        prefs(ctx).edit()
            .putString(Prefs.NPU_UI_ASPECT, aspect)
            .putString(Prefs.NPU_UI_SCHEDULER, scheduler)
            .putBoolean(Prefs.NPU_UI_KARRAS, karras)
            .putInt(Prefs.STEPS, steps)
            .putFloat(Prefs.CFG, cfg)
            .putInt(Prefs.NPU_UI_BATCH, batch)
            .putFloat(Prefs.NPU_UI_DENOISE, denoise)
            .putString(Prefs.NPU_UI_SEED, seed)
            .apply()
        _state.value = _state.value.copy(
            aspectRatio = aspect, scheduler = scheduler, karras = karras,
            steps = steps, cfg = cfg, batchCount = batch,
            denoiseStrength = denoise,
            seed = seed.toIntOrNull() ?: -1
        )
    }

    fun setSeed(v: Int)                   { _state.value = _state.value.copy(seed = v) }
    fun setLoraScale(v: Float)            { _state.value = _state.value.copy(loraScale = v) }
    fun clearLora() {
        _state.value = _state.value.copy(loraName = null)
        viewModelScope.launch {
            try { prefs.clearLoraName() } catch (_: Exception) {}
        }
    }
    fun dismissError()                    { _state.value = _state.value.copy(error = null) }

    /** Clears the currently displayed image. */
    fun clearImage() {
        _state.value = _state.value.copy(image = null, status = "")
    }

    // ---------- Generation ----------

    fun generate() {
        val s = _state.value
        if (!s.sdModelLoaded) {
            _state.value = s.copy(error = "Load an SD model first")
            return
        }
        if (s.prompt.isBlank()) {
            _state.value = s.copy(error = "Enter a prompt")
            return
        }
        if (s.isGenerating) return

        _state.value = s.copy(
            isGenerating = true,
            error = null,
            status = "Preparing…",
            progress = -1f,
            currentStep = 0,
            totalSteps = s.steps,
            elapsedSeconds = 0
        )

        // ---- Free the LLM to make room for SD ----
        try { com.localai.app.llama.LlamaBridge.freeModel() } catch (_: Exception) {}

        viewModelScope.launch {
            val startTime = System.currentTimeMillis()

            // Register progress callback
            val cb = object : StableDiffusionBridge.SDProgressCallback {
                override fun onStepProgress(step: Int, total: Int) {
                    _state.value = _state.value.copy(
                        currentStep = step,
                        totalSteps = total,
                        status = "Step $step / $total",
                        progress = if (total > 0) step.toFloat() / total else -1f
                    )
                }
            }
            StableDiffusionBridge.setSDProgressCallback(cb)

            // Elapsed timer coroutine
            val timerJob = viewModelScope.launch {
                while (_state.value.isGenerating) {
                    kotlinx.coroutines.delay(1000L)
                    if (_state.value.isGenerating) {
                        _state.value = _state.value.copy(
                            elapsedSeconds = _state.value.elapsedSeconds + 1
                        )
                    }
                }
            }

            // Build LoRA path (if any)
            val loraFile = _state.value.loraName?.let { File(getApplication<Application>().filesDir, it) }
            val loraPath = if (loraFile?.exists() == true) loraFile.absolutePath else ""

            val bytes = withContext(Dispatchers.IO) {
                try {
                    StableDiffusionBridge.generateImage(
                        prompt         = _state.value.prompt,
                        negativePrompt = _state.value.negativePrompt,
                        width          = _state.value.width,
                        height         = _state.value.height,
                        steps          = _state.value.steps,
                        cfg            = _state.value.cfg,
                        seed           = _state.value.seed,
                        loraPath       = loraPath,
                        loraScale      = _state.value.loraScale
                    )
                } catch (e: Exception) {
                    Log.e("ImageVM", "generate failed", e)
                    null
                }
            }

            if (bytes == null) {
                _state.value = _state.value.copy(
                    isGenerating = false,
                    error = "Generation failed",
                    status = "",
                    progress = -1f
                )
                return@launch
            }

            val bitmap = try {
                bytesToBitmap(bytes)
            } catch (e: Exception) {
                Log.e("ImageVM", "bitmap conversion failed", e)
                null
            }

            StableDiffusionBridge.setSDProgressCallback(null)
            timerJob.cancel()

            val elapsed = (System.currentTimeMillis() - startTime) / 1000
            _state.value = _state.value.copy(
                isGenerating = false,
                image = bitmap ?: _state.value.image,
                status = if (bitmap != null) "Done in ${elapsed}s" else "",
                progress = -1f,
                currentStep = 0,
                totalSteps = 0,
                error = if (bitmap == null) "Could not decode image" else null
            )
        }
    }

    // ---------- Internals ----------

    private suspend fun loadSDFromFile(file: File) {
        _state.value = _state.value.copy(status = "Loading SD model…", progress = 0f, error = null)
        val ok = withContext(Dispatchers.IO) {
            try {
                // For SD 1.5 all-in-one GGUF, VAE and CLIP are baked in.
                StableDiffusionBridge.initSDModel(
                    modelPath = file.absolutePath,
                    vaePath   = "",
                    clipPath  = ""
                )
            } catch (e: Exception) {
                Log.e("ImageVM", "initSDModel failed", e)
                false
            }
        }
        if (ok) {
            try { prefs.saveSdModelName(file.name) } catch (_: Exception) {}
        }

        _state.value = _state.value.copy(
            sdModelLoaded = ok,
            sdModelName   = if (ok) file.name else null,
            status        = "",
            progress      = -1f,
            error         = if (!ok) "Failed to load SD model" else null
        )
    }

    private fun copyWithProgress(uri: Uri, dst: File): File? {
        return try {
            val ctx = getApplication<Application>()
            val total = ctx.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dst).use { output ->
                    val buf = ByteArray(1 shl 20)
                    var copied = 0L
                    var n: Int
                    var lastPct = -1
                    while (input.read(buf).also { n = it } > 0) {
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = ((copied * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                _state.value = _state.value.copy(
                                    progress = copied.toFloat() / total,
                                    status = "Copying… $pct%"
                                )
                            }
                        }
                    }
                }
            }
            dst
        } catch (e: Exception) {
            Log.e("ImageVM", "copy failed", e)
            null
        }
    }

    private fun queryName(uri: Uri): String? {
        val ctx = getApplication<Application>()
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) { null }
    }

    /**
     * JNI output format:
     *   [0..3]   int32 width  (LE)
     *   [4..7]   int32 height (LE)
     *   [8..11]  int32 channels (3 = RGB, 4 = RGBA)
     *   [12..]   pixel bytes
     */
    private fun bytesToBitmap(bytes: ByteArray): Bitmap? {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val w = bb.getInt(0)
        val h = bb.getInt(4)
        val c = bb.getInt(8)

        if (w <= 0 || h <= 0 || (c != 3 && c != 4)) {
            Log.e("ImageVM", "bad header: w=$w h=$h c=$c")
            return null
        }

        val pixels = IntArray(w * h)
        val dataStart = 12
        if (c == 3) {
            for (i in 0 until w * h) {
                val o = dataStart + i * 3
                val r = bytes[o].toInt() and 0xFF
                val g = bytes[o + 1].toInt() and 0xFF
                val b = bytes[o + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        } else {
            for (i in 0 until w * h) {
                val o = dataStart + i * 4
                val r = bytes[o].toInt() and 0xFF
                val g = bytes[o + 1].toInt() and 0xFF
                val b = bytes[o + 2].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    override fun onCleared() {
        super.onCleared()
        try { StableDiffusionBridge.freeSDModel() } catch (_: Exception) {}
    }
}
