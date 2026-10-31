package com.localai.app.ui

import android.app.Application
import android.content.Context
import android.app.ActivityManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localai.app.llama.LlamaBridge
import com.localai.app.geniex.QuantaBridge
import com.localai.app.geniex.LiteRtBridge
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null
)

data class UiState(
    val npuReady: Boolean = false,
    val npuModelLoaded: Boolean = false,
    val liteRtModelLoaded: Boolean = false,
    val npuModelName: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val modelLoaded: Boolean = false,
    val modelName: String? = null,
    val error: String? = null,
    val status: String = "",
    val progress: Float = -1f,           // -1 = indeterminate, 0..1 = real progress
    // ---- Vision support (populated when a vision model + mmproj are loaded) ----
    val mmprojName: String? = null,
    val visionCapable: Boolean = false,
    val pendingImages: List<File> = emptyList(),
    // ---- Conversations ----
    val conversations: List<ConversationMeta> = emptyList(),
    val currentConversationId: String? = null,
    // ---- Model downloads ----
    val downloads: Map<String, DownloadState> = emptyMap(),
    // ---- Web search ----
    val webSearchEnabled: Boolean = false,
    val isSearching: Boolean = false,
    val lastSearchSource: String? = null
)

@HiltViewModel
class LlamaViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var idleUnloadJob: kotlinx.coroutines.Job? = null

    private var currentConversationId: String =
        "chat_" + System.currentTimeMillis().toString(36)

    init {
        // One-shot: clear USE_GPU pref (OpenCL was removed, saved true
        // values would otherwise attempt an unavailable backend).
        try {
            val sp = getApplication<Application>()
                .getSharedPreferences("localai_prefs", android.content.Context.MODE_PRIVATE)
            if (!sp.getBoolean("OPENCL_MIGRATED", false)) {
                sp.edit().putBoolean("use_gpu", false)
                          .putBoolean("OPENCL_MIGRATED", true)
                          .commit()
                Log.i("LlamaVM", "Cleared USE_GPU pref (OpenCL removed)")
            }
        } catch (_: Throwable) {}

        // One-shot: reset saved threads to 4 (old default of 6 ran hot)
        try {
            val sp = getApplication<Application>()
                .getSharedPreferences("localai_prefs", android.content.Context.MODE_PRIVATE)
            if (!sp.getBoolean("THREADS_MIGRATED_V2", false)) {
                val current = sp.getInt("threads", 4)
                sp.edit().putInt("threads", 4)
                          .putBoolean("THREADS_MIGRATED_V2", true)
                          .commit()
                Log.i("LlamaVM", "Threads migrated from $current to 4")
            }
        } catch (_: Throwable) {}

        // Load the saved conversation list on startup
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val list = withContext(Dispatchers.IO) { ConversationStore.list(ctx) }
            _state.value = _state.value.copy(
                conversations = list,
                currentConversationId = currentConversationId
            )
        }
    }

    /**
     * Loads a GGUF model. `uri` comes from the SAF file picker.
     * The native side needs a real filesystem path, so we copy the URI
     * into the app's internal files dir first.
     */
    fun loadModel(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                error = null, modelName = null, modelLoaded = false,
                status = "Copying model…", progress = 0f
            )

            val ctx = getApplication<Application>()
            val name = queryDisplayName(uri) ?: "model.gguf"

            // If we already have a file with this name, skip the copy.
            val existing = File(ctx.filesDir, name)
            if (existing.exists() && existing.length() > 0) {
                loadFromFile(existing)
                return@launch
            }

            // --- Phase 1: copy with real byte progress ---
            val localFile = withContext(Dispatchers.IO) {
                try {
                    val dst = File(ctx.filesDir, name)
                    val total = ctx.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize } ?: -1L

                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dst).use { output ->
                            val buf = ByteArray(1 shl 20) // 1 MB
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
                                            status = "Copying model… $pct%"
                                        )
                                    }
                                }
                            }
                        }
                    }
                    dst
                } catch (e: Exception) {
                    Log.e("LlamaVM", "copy failed", e)
                    null
                }
            }

            if (localFile == null || !localFile.exists()) {
                _state.value = _state.value.copy(
                    error = "Could not copy model file", progress = -1f
                )
                return@launch
            }

            val ramErr = checkRamForModel(localFile)
            if (ramErr != null) {
                _state.value = _state.value.copy(
                    error = "RAM_WARN::" + ramErr + "::" + localFile.absolutePath,
                    progress = -1f, status = ""
                )
                return@launch
            }

            loadFromFile(localFile)
        }
    }
    /** Loads an already-installed GGUF file from the app's internal storage. */
    fun loadInstalledModel(file: File, force: Boolean = false) {
        // If an NPU model is loaded, free it first — mixed residency tanks RAM.
        if (QuantaBridge.isModelLoaded()) {
            QuantaBridge.unload()
            _state.value = _state.value.copy(npuModelLoaded = false)
        }

        // Free any previously-loaded model FIRST so its RAM counts as available
        val wasLoaded = _state.value.modelLoaded
        if (wasLoaded) {
            try { LlamaBridge.freeMmproj() } catch (_: Throwable) {}
            try { LlamaBridge.freeModel() } catch (_: Throwable) {}
            _state.value = _state.value.copy(
                modelLoaded = false,
                modelName = null,
                visionCapable = false,
                mmprojName = null
            )
        }

        if (!force) {
            val ramErr = checkRamForModel(file)
            if (ramErr != null) {
                // Preserve the HARD_RAM/RAM_WARN prefix and append the path
                _state.value = _state.value.copy(
                    error = "RAM_WARN::" + ramErr + "::" + file.absolutePath,
                )
                return
            }
        }
        viewModelScope.launch { loadFromFile(file) }
    }

    /** Force-loads the model named in the error string, bypassing RAM check. */
    fun forceLoadLastRamError() {
        val err = _state.value.error ?: return
        if (!err.startsWith("RAM_WARN::")) return
        val parts = err.split("::")
        if (parts.size < 3) return
        val path = parts[2]
        val file = java.io.File(path)
        if (!file.exists()) {
            _state.value = _state.value.copy(error = "Model file not found")
            return
        }
        _state.value = _state.value.copy(error = null)
        loadInstalledModel(file, force = true)
    }

    /** Lists every .gguf file currently stored, EXCLUDING vision projectors. */
    /** Lists .litertlm files in the app's internal files dir. */
    fun listInstalledLiteRtModels(): List<File> {
        val ctx = getApplication<Application>()
        return ctx.filesDir.listFiles { f ->
            f.isFile && f.name.endsWith(".litertlm", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun deleteInstalledLiteRtModel(file: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (LiteRtBridge.isModelLoaded() && _state.value.modelName == file.name) {
                        LiteRtBridge.unload()
                        _state.value = _state.value.copy(
                            modelLoaded = false, liteRtModelLoaded = false,
                            modelName = null, visionCapable = false
                        )
                    }
                    kotlinx.coroutines.delay(500)
                    if (file.exists() && !file.delete()) {
                        Log.e("LlamaVM", "Failed to delete LiteRT file: ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e("LlamaVM", "delete litertlm failed", e)
                }
            }
        }
    }

    fun listInstalledModels(): List<File> {
        return getApplication<Application>().filesDir
            .listFiles { f ->
                f.isFile &&
                f.name.endsWith(".gguf", ignoreCase = true) &&
                !f.name.contains("mmproj", ignoreCase = true)
            }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Deletes an installed model. If it's the current one, unloads first. */
    fun deleteInstalledModel(file: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    if (_state.value.modelName == file.name) {
                        if (_state.value.npuModelLoaded) {
                            QuantaBridge.unload()
                        } else {
                            LlamaBridge.freeMmproj()
                            LlamaBridge.freeModel()
                        }
                        _state.value = _state.value.copy(
                            modelLoaded = false, npuModelLoaded = false, modelName = null
                        )
                    }
                    // Give the OS time to release memory-mapped file handles
                    kotlinx.coroutines.delay(500)
                    if (file.exists() && !file.delete()) {
                        Log.e("LlamaVM", "Failed to delete GGUF file: ${file.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e("LlamaVM", "delete failed", e)
                }
            }
        }
    }

    // ============================================================
    // Vision support
    // ============================================================

    /** Loads a vision projector (mmproj). Currently a stub — will activate
     *  when llama.cpp's mtmd module is vendored into the native lib. */
    fun loadMmproj(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, status = "Copying projector…", progress = 0f)
            val ctx = getApplication<Application>()
            val name = queryDisplayName(uri) ?: "mmproj.gguf"
            val existing = File(ctx.filesDir, name)
            val local: File? = if (existing.exists() && existing.length() > 0) {
                existing
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        val dst = File(ctx.filesDir, name)
                        val total = ctx.contentResolver.openFileDescriptor(uri, "r")
                            ?.use { it.statSize } ?: -1L
                        ctx.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(dst).use { output ->
                                val buf = ByteArray(1 shl 20)
                                var copied = 0L; var n: Int
                                var lastPct = -1
                                while (input.read(buf).also { n = it } > 0) {
                                    output.write(buf, 0, n); copied += n
                                    if (total > 0) {
                                        val pct = ((copied * 100) / total).toInt()
                                        if (pct != lastPct) {
                                            lastPct = pct
                                            _state.value = _state.value.copy(
                                                progress = copied.toFloat() / total,
                                                status = "Copying projector… $pct%"
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        dst
                    } catch (e: Exception) {
                        Log.e("LlamaVM", "mmproj copy failed", e); null
                    }
                }
            }

            if (local == null) {
                _state.value = _state.value.copy(
                    error = "Could not copy projector", progress = -1f, status = ""
                )
                return@launch
            }

            val ok = withContext(Dispatchers.IO) {
                try { LlamaBridge.loadMmproj(local.absolutePath) } catch (e: Exception) {
                    Log.e("LlamaVM", "loadMmproj failed", e); false
                }
            }
            val capable = LlamaBridge.isVisionCapable()
            _state.value = _state.value.copy(
                mmprojName = if (ok || capable) local.name else null,
                visionCapable = capable,
                status = "",
                progress = -1f,
                error = if (!ok && !capable) "Vision not yet enabled in this build" else null
            )
        }
    }

    /** Lists installed mmproj (vision projector) files. */
    fun listInstalledMmprojs(): List<File> =
        getApplication<Application>().filesDir
            .listFiles { f -> f.isFile && f.name.contains("mmproj", ignoreCase = true) && f.name.endsWith(".gguf", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    /** Saves an image and adds it to the pending set for the next prompt. */
    fun attachImage(uri: Uri) {
        if (!_state.value.visionCapable) {
            _state.value = _state.value.copy(error = "Vision model not loaded")
            return
        }
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val name = queryDisplayName(uri) ?: "image_${System.currentTimeMillis()}.jpg"
            val imgDir = File(ctx.filesDir, "chat-images")
            imgDir.mkdirs()
            val dst = File(imgDir, name)
            withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(dst).use { out -> input.copyTo(out) }
                    }
                } catch (e: Exception) {
                    Log.e("LlamaVM", "attach image copy failed", e)
                }
            }
            if (dst.exists()) {
                _state.value = _state.value.copy(
                    pendingImages = _state.value.pendingImages + dst,
                    error = null
                )
            }
        }
    }

    fun removeImage(file: File) {
        _state.value = _state.value.copy(
            pendingImages = _state.value.pendingImages.filter { it != file }
        )
    }

    fun clearImages() {
        _state.value = _state.value.copy(pendingImages = emptyList())
    }

    /** Deletes an installed mmproj file from internal storage. */
    fun deleteInstalledMmproj(file: File) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { file.delete() }
            if (_state.value.mmprojName == file.name) {
                _state.value = _state.value.copy(
                    mmprojName = null,
                    visionCapable = false
                )
            }
        }
    }

    /** Loads an already-installed mmproj file (from the model picker). */
    fun loadMmprojFromFile(file: File) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try { LlamaBridge.loadMmproj(file.absolutePath) }
                catch (e: Exception) { Log.e("LlamaVM", "loadMmprojFromFile failed", e); false }
            }
            val capable = LlamaBridge.isVisionCapable()
            _state.value = _state.value.copy(
                mmprojName = if (ok || capable) file.name else null,
                visionCapable = capable,
                error = if (!ok && !capable) "Vision not yet enabled in this build" else null
            )
        }
    }

    // ============================================================
    // Conversations
    // ============================================================

    /** Start a fresh conversation and clear the message list. */
    fun newConversation() {
        val ctx = getApplication<Application>()
        val currentMessages = _state.value.messages
        // CAPTURE the old id NOW — the coroutine reads the field later,
        // and we're about to overwrite it.
        val oldId = currentConversationId

        if (currentMessages.isNotEmpty()) {
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    ConversationStore.save(
                        ctx,
                        oldId,
                        ConversationStore.titleFrom(currentMessages),
                        currentMessages
                    )
                    // Refresh the list so the just-saved chat appears immediately
                    val list = ConversationStore.list(ctx)
                    _state.value = _state.value.copy(conversations = list)
                }
            }
        }

        currentConversationId = "chat_" + System.currentTimeMillis().toString(36)
        _state.value = _state.value.copy(
            messages = emptyList(),
            currentConversationId = currentConversationId,
            pendingImages = emptyList()
        )
    }

    /** Load a saved conversation into the active chat. */
    fun loadConversation(id: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val currentMessages = _state.value.messages
            val outgoingId = currentConversationId   // capture before we change it
            if (currentMessages.isNotEmpty() && outgoingId != id) {
                withContext(Dispatchers.IO) {
                    ConversationStore.save(
                        ctx,
                        outgoingId,
                        ConversationStore.titleFrom(currentMessages),
                        currentMessages
                    )
                }
            }
            val messages = withContext(Dispatchers.IO) { ConversationStore.load(ctx, id) }
            currentConversationId = id
            _state.value = _state.value.copy(
                messages = messages,
                currentConversationId = id,
                pendingImages = emptyList()
            )
        }
    }

    /** Delete a saved conversation. */
    fun deleteConversation(id: String) {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            withContext(Dispatchers.IO) { ConversationStore.delete(ctx, id) }
            val list = withContext(Dispatchers.IO) { ConversationStore.list(ctx) }
            if (id == currentConversationId) {
                // If we deleted the active one, start a fresh session
                currentConversationId = "chat_" + System.currentTimeMillis().toString(36)
                _state.value = _state.value.copy(
                    messages = emptyList(),
                    currentConversationId = currentConversationId,
                    conversations = list
                )
            } else {
                _state.value = _state.value.copy(conversations = list)
            }
        }
    }

    /** Internal — persist the current conversation after each change. */
    private fun persistCurrent() {
        val ctx = getApplication<Application>()
        val messages = _state.value.messages
        val id = currentConversationId   // capture now — coroutine runs later
        if (messages.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ConversationStore.save(
                    ctx,
                    id,
                    ConversationStore.titleFrom(messages),
                    messages
                )
                val list = ConversationStore.list(ctx)
                _state.value = _state.value.copy(conversations = list)
            }
        }
    }

    /**
     * Estimates whether `file` will fit in available RAM.
     *
     * Rule of thumb:
     *   loadedSize ≈ fileSize * 1.3
     *     +1.0 for KV cache / compute buffers (~20%)
     *     +0.1 for alignment / fragmentation
     *   We require loadedSize < availableRam * 0.75 to leave headroom
     *   for the OS + other apps during use.
     *
     * Returns null if OK, or a user-facing error message if not.
     */
    private fun checkRamForModel(file: File): String? {
        return try {
            val ctx = getApplication<Application>()
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)

            val availMb = mi.availMem / 1_048_576L
            val fileMb  = file.length() / 1_048_576L
            val neededMb = (fileMb * 1.3).toLong()

            Log.i("LlamaVM", "RAM check: file=${fileMb}MB needed=${neededMb}MB avail=${availMb}MB")

            if (neededMb > (availMb * 0.75).toLong()) {
                val needGb   = "%.1f".format(neededMb / 1024.0)
                val availGb  = "%.1f".format(availMb / 1024.0)
                "Model needs ~${needGb} GB RAM but only ${availGb} GB is free. " +
                        "Close other apps or use a smaller quant (Q4_0)."
            } else null
        } catch (t: Throwable) {
            Log.e("LlamaVM", "RAM check failed — allowing load", t)
            null   // fail-open: if we can't measure, don't block the user
        }
    }

    // ============================================================
    // Model downloads
    // ============================================================

    /** Tracks running download jobs so they can be cancelled. */
    private val downloadJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val cancelledDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ---- Text model downloads through Android's DownloadManager ----
    // Android does the download itself, so it keeps going in the background,
    // survives screen-off and resumes after network drops.
    private fun dmPrefs() = getApplication<Application>()
        .getSharedPreferences("llama_dm_downloads", android.content.Context.MODE_PRIVATE)

    private fun dmManager() = getApplication<Application>()
        .getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager

    private fun cancelDmDownload(modelId: String) {
        try {
            val id = dmPrefs().getLong("dm_$modelId", -1L)
            if (id != -1L) dmManager().remove(id)
            dmPrefs().edit().remove("dm_$modelId").apply()
        } catch (t: Throwable) {
            Log.e("LlamaVM", "Could not cancel DownloadManager entry", t)
        }
    }

    fun startDownload(model: DownloadableModel) {
        if (downloadJobs[model.id]?.isActive == true) return  // already running

        val ctx = getApplication<Application>()
        val targetDir = ctx.filesDir
        val finalFile = java.io.File(targetDir, model.fileName)

        // Seed initial state
        _state.value = _state.value.copy(
            downloads = _state.value.downloads + (model.id to DownloadState(
                modelId = model.id,
                progress = 0f,
                bytesRead = 0L,
                bytesTotal = -1L,
                status = "Starting…"
            )),
            error = null
        )

        cancelledDownloads.remove(model.id)
        val job = viewModelScope.launch {
            try {
                // Already installed? Just load it.
                if (finalFile.exists() && finalFile.length() > 0) {
                    _state.value = _state.value.copy(
                        downloads = _state.value.downloads + (model.id to DownloadState(
                            modelId = model.id,
                            progress = 1f,
                            bytesRead = finalFile.length(),
                            bytesTotal = finalFile.length(),
                            status = "Download complete",
                            isComplete = true
                        ))
                    )
                    loadInstalledModel(finalFile)
                    return@launch
                }

                val dm = dmManager()
                val prefs = dmPrefs()
                val extDir = ctx.getExternalFilesDir(null) ?: throw Exception("Storage is not available")
                val expectedFile = java.io.File(extDir, "model-downloads/${model.fileName}")
                var downloadId = prefs.getLong("dm_${model.id}", -1L)

                // Re-attach to an earlier download if Android still has it
                if (downloadId != -1L) {
                    var usable = false
                    val old = dm.query(android.app.DownloadManager.Query().setFilterById(downloadId))
                    if (old != null) {
                        try {
                            if (old.moveToFirst()) {
                                val st = old.getInt(old.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                                usable = st != android.app.DownloadManager.STATUS_FAILED
                            }
                        } finally { old.close() }
                    }
                    if (!usable) {
                        try { dm.remove(downloadId) } catch (_: Throwable) {}
                        prefs.edit().remove("dm_${model.id}").apply()
                        downloadId = -1L
                    }
                }

                // Otherwise start a new download
                if (downloadId == -1L) {
                    expectedFile.parentFile?.mkdirs()
                    if (expectedFile.exists()) expectedFile.delete()
                    val request = android.app.DownloadManager.Request(android.net.Uri.parse(model.url))
                        .setTitle(model.displayName)
                        .setDescription("Downloading AI model (${model.sizeLabel})")
                        .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(ctx, null, "model-downloads/${model.fileName}")
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(true)
                    downloadId = dm.enqueue(request)
                    prefs.edit().putLong("dm_${model.id}", downloadId).apply()
                }

                // Follow progress until Android reports the download is finished
                var sourcePath: String? = null
                while (sourcePath == null) {
                    kotlinx.coroutines.delay(1000)
                    var st = -1
                    var read = 0L
                    var total = -1L
                    var reason = 0
                    var localUri: String? = null
                    val c = dm.query(android.app.DownloadManager.Query().setFilterById(downloadId))
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                st = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS))
                                read = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                total = c.getLong(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                reason = c.getInt(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_REASON))
                                localUri = c.getString(c.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_LOCAL_URI))
                            }
                        } finally { c.close() }
                    }
                    if (st == -1) throw Exception("Download was removed")
                    if (st == android.app.DownloadManager.STATUS_FAILED) throw Exception("Download failed (code $reason)")
                    if (st == android.app.DownloadManager.STATUS_SUCCESSFUL) {
                        sourcePath = localUri?.let { android.net.Uri.parse(it).path } ?: expectedFile.absolutePath
                    } else {
                        val prog = if (total > 0) read.toFloat() / total else 0f
                        val pct = (prog * 100).toInt()
                        val statusText = when {
                            st == android.app.DownloadManager.STATUS_PAUSED -> "Waiting for network… $pct%"
                            total > 0 -> "Downloading… $pct%"
                            else -> "Downloading… ${read / 1_048_576} MB"
                        }
                        _state.value = _state.value.copy(
                            downloads = _state.value.downloads + (model.id to DownloadState(
                                modelId = model.id,
                                progress = prog,
                                bytesRead = read,
                                bytesTotal = total,
                                status = statusText
                            ))
                        )
                    }
                }

                // Move the finished file into the app's model folder
                val sourceFile = java.io.File(sourcePath ?: expectedFile.absolutePath)
                if (!sourceFile.exists()) throw Exception("Downloaded file not found")
                val sizeBytes = sourceFile.length()
                _state.value = _state.value.copy(
                    downloads = _state.value.downloads + (model.id to DownloadState(
                        modelId = model.id,
                        progress = 1f,
                        bytesRead = sizeBytes,
                        bytesTotal = sizeBytes,
                        status = "Installing model…"
                    ))
                )
                withContext(Dispatchers.IO) {
                    targetDir.mkdirs()
                    val part = java.io.File(targetDir, "${model.fileName}.part")
                    try {
                        if (part.exists()) part.delete()
                        if (finalFile.exists()) finalFile.delete()
                        if (!sourceFile.renameTo(finalFile)) {
                            sourceFile.inputStream().use { input ->
                                part.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
                            }
                            if (!part.renameTo(finalFile)) throw Exception("Could not install the model file")
                            sourceFile.delete()
                        }
                    } catch (t: Throwable) {
                        try { part.delete() } catch (_: Throwable) {}
                        throw t
                    }
                }

                // Clean up Android's download record
                try { dm.remove(downloadId) } catch (_: Throwable) {}
                prefs.edit().remove("dm_${model.id}").apply()

                _state.value = _state.value.copy(
                    downloads = _state.value.downloads + (model.id to DownloadState(
                        modelId = model.id,
                        progress = 1f,
                        bytesRead = finalFile.length(),
                        bytesTotal = finalFile.length(),
                        status = "Download complete",
                        isComplete = true
                    ))
                )

                // Auto-load the newly-downloaded model
                loadInstalledModel(finalFile)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                _state.value = _state.value.copy(
                    downloads = _state.value.downloads + (model.id to DownloadState(
                        modelId = model.id,
                        progress = 0f,
                        bytesRead = 0L,
                        bytesTotal = -1L,
                        status = "Cancelled"
                    ))
                )
                throw ce
            } catch (t: Throwable) {
                Log.e("LlamaVM", "Download failed: ${model.id}", t)
                _state.value = _state.value.copy(
                    downloads = _state.value.downloads + (model.id to DownloadState(
                        modelId = model.id,
                        progress = 0f,
                        bytesRead = 0L,
                        bytesTotal = -1L,
                        status = "Failed",
                        error = t.message ?: "Download failed"
                    ))
                )
            } finally {
                downloadJobs.remove(model.id)
            }
        }
        downloadJobs[model.id] = job
    }

    fun cancelDownload(modelId: String) {
        cancelDmDownload(modelId)
        cancelledDownloads.add(modelId)
        downloadJobs[modelId]?.cancel()
        downloadJobs.remove(modelId)
        _state.value = _state.value.copy(
            downloads = _state.value.downloads - modelId
        )
    }

    fun clearDownloadState(modelId: String) {
        _state.value = _state.value.copy(
            downloads = _state.value.downloads - modelId
        )
    }

    /** Returns true if the catalog model is already installed. */
    fun isCatalogModelInstalled(model: DownloadableModel): Boolean {
        val ctx = getApplication<Application>()
        val f = File(ctx.filesDir, model.fileName)
        return f.exists() && f.length() > 0
    }

    /**
     * Decodes an image file, resizes it to <= 512px on the long edge, and
     * returns a ByteArray laid out for mtmd_bitmap_init():
     *   [0..3]   int32 LE width
     *   [4..7]   int32 LE height
     *   [8..11]  int32 LE channels (always 3 = RGB)
     *   [12..]   RGBRGB... pixel bytes
     * Returns null on any decode failure.
     */
    private fun encodeImageForVision(file: File): ByteArray? {
        return try {
            val original = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?: return null

            val maxDim = 512
            val longest = maxOf(original.width, original.height)
            val scaled = if (longest > maxDim) {
                val ratio = maxDim.toFloat() / longest
                android.graphics.Bitmap.createScaledBitmap(
                    original,
                    (original.width * ratio).toInt(),
                    (original.height * ratio).toInt(),
                    true
                )
            } else original

            val w = scaled.width
            val h = scaled.height
            val pixels = IntArray(w * h)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)

            val out = ByteArray(12 + w * h * 3)
            // width (LE)
            out[0] = (w and 0xFF).toByte()
            out[1] = ((w ushr 8) and 0xFF).toByte()
            out[2] = ((w ushr 16) and 0xFF).toByte()
            out[3] = ((w ushr 24) and 0xFF).toByte()
            // height (LE)
            out[4] = (h and 0xFF).toByte()
            out[5] = ((h ushr 8) and 0xFF).toByte()
            out[6] = ((h ushr 16) and 0xFF).toByte()
            out[7] = ((h ushr 24) and 0xFF).toByte()
            // channels (LE) = 3
            out[8] = 3
            out[9] = 0
            out[10] = 0
            out[11] = 0

            var p = 12
            for (i in 0 until w * h) {
                val c = pixels[i]
                out[p++] = ((c shr 16) and 0xFF).toByte()   // R
                out[p++] = ((c shr 8)  and 0xFF).toByte()   // G
                out[p++] = (c and 0xFF).toByte()            // B
            }

            if (scaled !== original) scaled.recycle()
            original.recycle()

            Log.i("LlamaVM", "encoded image ${file.name}: ${w}x${h} -> ${out.size} bytes")
            out
        } catch (t: Throwable) {
            Log.e("LlamaVM", "image encode failed: ${file.name}", t)
            null
        }
    }

    /**
     * Scans filesDir for a mmproj matching the just-loaded text model's family
     * (Qwen/LLaVA/MiniCPM/...). If found, loads it automatically so the user
     * doesn't have to pick mmproj separately.
     */
    private suspend fun autoLoadMatchingMmproj(modelFile: File) {
        val ctx = getApplication<Application>()

        _state.value = _state.value.copy(status = "Scanning for vision projector…")

        val mmprojs = ctx.filesDir.listFiles { f ->
            f.isFile &&
            f.name.contains("mmproj", ignoreCase = true) &&
            f.name.endsWith(".gguf", ignoreCase = true)
        } ?: emptyArray()

        Log.i("LlamaVM", "auto-detect: model=${modelFile.name}, found ${mmprojs.size} mmproj files")

        if (mmprojs.isEmpty()) {
            _state.value = _state.value.copy(status = "")
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "No mmproj found in app storage", Toast.LENGTH_LONG).show()
            }
            return
        }

        val modelLower = modelFile.name.lowercase()
        val best = mmprojs.maxByOrNull { mp ->
            val mpLower = mp.name.lowercase()
            var score = 0
            listOf("qwen", "llava", "minicpm", "gemma", "internvl",
                   "pixtral", "phi", "mllama", "molmo", "smolvlm")
                .forEach { fam ->
                    if (mpLower.contains(fam) && modelLower.contains(fam)) score += 10
                }
            listOf("0.5b", "2b", "3b", "4b", "7b", "8b", "11b", "13b")
                .forEach { sz ->
                    if (mpLower.contains(sz) && modelLower.contains(sz)) score += 5
                }
            score
        }
        if (best == null) {
            _state.value = _state.value.copy(status = "")
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx, "No matching mmproj found", Toast.LENGTH_LONG).show()
            }
            return
        }

        withContext(Dispatchers.Main) {
            Toast.makeText(ctx, "Loading projector: ${best.name}", Toast.LENGTH_LONG).show()
        }
        _state.value = _state.value.copy(status = "Loading projector: ${best.name}")

        withContext(Dispatchers.IO) {
            try {
                val ok = LlamaBridge.loadMmproj(best.absolutePath)
                val capable = LlamaBridge.isVisionCapable()
                _state.value = _state.value.copy(
                    mmprojName = if (capable) best.name else null,
                    visionCapable = capable,
                    status = ""
                )
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        ctx,
                        if (capable) "Vision ready: ${best.name}" else "Projector load failed",
                        Toast.LENGTH_LONG
                    ).show()
                }
                Log.i("LlamaVM", "Auto-loaded mmproj: ${best.name}, capable=$capable")
            } catch (e: Exception) {
                _state.value = _state.value.copy(status = "")
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Projector error: ${e.message}", Toast.LENGTH_LONG).show()
                }
                Log.e("LlamaVM", "auto mmproj failed", e)
            }
        }
    }

    // ============================================================
    // Web search
    // ============================================================

    fun toggleWebSearch() {
        _state.value = _state.value.copy(
            webSearchEnabled = !_state.value.webSearchEnabled
        )
    }

    /**
     * Runs a web search for the given query and returns a formatted block
     * suitable for prepending to the model prompt. Returns empty string on
     * failure so the send() path can just fall back to plain generation.
     */
    private suspend fun runWebSearch(query: String): String {
        _state.value = _state.value.copy(isSearching = true, error = null)
        return try {
            val response = SearchService.search(query)
            _state.value = _state.value.copy(
                isSearching = false,
                lastSearchSource = if (response.results.isNotEmpty()) response.source else null
            )
            if (response.results.isEmpty()) {
                Log.i("LlamaVM", "Web search returned no results for: $query")
                ""
            } else {
                Log.i("LlamaVM", "Web search (${response.source}) returned ${response.results.size} results")
                SearchService.formatForPrompt(query, response)
            }
        } catch (t: Throwable) {
            Log.e("LlamaVM", "Web search failed", t)
            _state.value = _state.value.copy(isSearching = false)
            ""
        }
    }

    // --- internal ---

    /** AI Hub NPU bundles known to work with GenieX on Snapdragon 8 Gen 3. */
    fun listAvailableNpuModels(): List<String> = listOf(
        "qualcomm/Qwen2.5-7B-Instruct",
        "qualcomm/Qwen2-7B-Instruct",
        "qualcomm/Llama-v3-8B-Instruct",
    )

    /** SAF-picked GGUFs → internal storage → Hexagon NPU. Accepts multiple
     *  URIs so split shards (-00001-of-00002 etc.) are copied together. */
    fun loadModelsOnHexagon(uris: List<Uri>) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                error = null, modelName = null,
                modelLoaded = false, npuModelLoaded = false,
                status = "Copying model…", progress = 0f
            )
            val ctx = getApplication<Application>()
            var mainPath: String? = null
            var mainName: String? = null

            val copied = withContext(Dispatchers.IO) {
                val out = mutableListOf<Pair<String, String>>()
                for (u in uris) {
                    val name = queryDisplayName(u) ?: continue
                    if (!name.endsWith(".gguf", ignoreCase = true)) continue
                    val dst = File(ctx.filesDir, name)
                    if (!dst.exists() || dst.length() == 0L) {
                        try {
                            ctx.contentResolver.openInputStream(u)?.use { input ->
                                FileOutputStream(dst).use { o -> input.copyTo(o) }
                            }
                        } catch (e: Exception) {
                            Log.e("LlamaVM", "copy failed for $name", e)
                            continue
                        }
                    }
                    out += name to dst.absolutePath
                }
                out
            }

            if (copied.isEmpty()) {
                _state.value = _state.value.copy(
                    error = "No GGUF files copied", progress = -1f, status = ""
                )
                return@launch
            }

            // Pick the first shard if split, otherwise the only file.
            val main = copied.firstOrNull { it.first.contains("-00001-of-") }
                ?: copied.first()
            mainName = main.first
            mainPath = main.second

            _state.value = _state.value.copy(status = "Loading on Hexagon NPU…")
            // Free any prior model so its RAM/buffers aren't held alongside the new one.
            if (QuantaBridge.isModelLoaded()) QuantaBridge.unload()
            QuantaBridge.loadFromPath(mainPath!!).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        modelLoaded = true, npuModelLoaded = true,
                        modelName = mainName, status = "", progress = -1f
                    )
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        error = "Hexagon load failed: ${it.message}",
                        status = "", progress = -1f
                    )
                },
            )
        }
    }

    /** Loads a .litertlm model on the NPU via LiteRT-LM. */
    fun loadLiteRtModel(context: android.content.Context, file: java.io.File) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                status = "Loading LiteRT NPU model…", error = null,
                modelLoaded = false, npuModelLoaded = false, liteRtModelLoaded = false
            )
            if (QuantaBridge.isModelLoaded()) QuantaBridge.unload()
            if (LiteRtBridge.isModelLoaded()) LiteRtBridge.unload()

            val result = LiteRtBridge.loadFromPath(context, file.absolutePath)
            _state.value = result.fold(
                onSuccess = {
                    _state.value.copy(
                        modelLoaded = true, liteRtModelLoaded = true,
                        modelName = file.name, status = "", progress = -1f,
                        visionCapable = LiteRtBridge.isVisionCapable(),
                    )
                },
                onFailure = {
                    _state.value.copy(
                        error = "LiteRT load failed: ${it.message}",
                        status = "", progress = -1f, visionCapable = false
                    )
                },
            )
        }
    }

    /** Loads an already-internal-storage GGUF onto the Hexagon NPU. */
    fun loadGgufOnHexagon(file: java.io.File) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                status = "Loading on Hexagon NPU…", error = null,
                modelLoaded = false, npuModelLoaded = false
            )
            if (QuantaBridge.isModelLoaded()) QuantaBridge.unload()
            QuantaBridge.loadFromPath(file.absolutePath).fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        modelLoaded = true, npuModelLoaded = true,
                        modelName = file.name, status = "", progress = -1f
                    )
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        error = "Hexagon load failed: ${it.message}",
                        status = "", progress = -1f
                    )
                },
            )
        }
    }

    /** Loads a GGUF from an arbitrary path onto the NPU via GenieX. */
    private fun scheduleIdleUnload() {
        idleUnloadJob?.cancel()
        idleUnloadJob = viewModelScope.launch {
            kotlinx.coroutines.delay(60_000L)
            if (QuantaBridge.isModelLoaded()) {
                QuantaBridge.unload()
                _state.value = _state.value.copy(
                    npuModelLoaded = false, modelLoaded = false,
                    modelName = null, status = "NPU model unloaded (idle)"
                )
            }
        }
    }

    private suspend fun loadFromFile(file: File) {
        _state.value = _state.value.copy(
            status = "Loading model into RAM…", progress = 0f, error = null
        )

        val callback = object : LlamaBridge.ProgressCallback {
            override fun onNativeProgress(progress: Float) {
                val pct = (progress * 100).toInt()
                _state.value = _state.value.copy(
                    progress = progress,
                    status = "Loading model… $pct%"
                )
            }
        }
        LlamaBridge.setProgressCallback(callback)

        val threads = prefs(getApplication()).getInt(Prefs.THREADS, 4)

        val ok = withContext(Dispatchers.IO) {
            LlamaBridge.initModel(file.absolutePath, threads)
        }
        LlamaBridge.setProgressCallback(null)

        _state.value = _state.value.copy(
            modelLoaded = ok,
            modelName = if (ok) file.name else null,
            error = if (!ok) "Failed to load model" else null,
            status = "",
            progress = -1f
        )

        if (ok) autoLoadMatchingMmproj(file)
    }

    private val SEARCH_SYSTEM_PROMPT =
        "You are a helpful assistant. Web search results are provided in " +
        "the user's message. Read them and answer the question based on " +
        "what those results actually say."

fun send(text: String) {
        // Allow empty text when an image is attached (vision send).
        if ((text.isBlank() && _state.value.pendingImages.isEmpty()) || _state.value.isGenerating) return
        if (!_state.value.modelLoaded) {
            _state.value = _state.value.copy(error = "Load a model first")
            return
        }

        // Append user message
        val pendingImagePath = _state.value.pendingImages.firstOrNull()?.absolutePath

        // Keep the message text CLEAN — the media marker will be injected
        // during prompt building, not stored in the message.
        _state.value = _state.value.copy(
            messages = _state.value.messages + ChatMessage(
                text = text, isUser = true, imagePath = pendingImagePath
            ),
            isGenerating = true,
            error = null
        )

        viewModelScope.launch {
            // If web search is enabled, run it first and prepend the results
            val searchBlock = if (_state.value.webSearchEnabled) {
                runWebSearch(text)
            } else ""

            // Build the prompt — searchBlock is injected inside the
            // last user turn by buildPrompt() so the model actually sees it.
            val prompt = buildPrompt(_state.value.messages, searchBlock)

            val imagePaths = _state.value.pendingImages.map { it.absolutePath }.toTypedArray()
            // Clear the pending set NOW so images disappear from the input bar
            // the moment the user hits send. They're already captured in
            // imagePaths / the message list.
            _state.value = _state.value.copy(pendingImages = emptyList())

            // Read live settings from prefs so the Settings-tab sliders work
            val ctx = getApplication<Application>()
            val p = prefs(ctx)
            val temperature = p.getFloat(Prefs.TEMPERATURE, 0.8f)
            val topP = p.getFloat(Prefs.TOP_P, 0.95f)
            val maxTokens = p.getInt(Prefs.MAX_TOKENS, 256)
            val topK = 40  // not exposed in UI yet

            // Register a streaming callback BEFORE starting generation.
            // Native invokes onToken() on the worker thread; each token
            // is appended to the last (assistant) message in state.
            _state.value = _state.value.copy(
                messages = _state.value.messages + ChatMessage("", isUser = false)
            )
            val streamingIdx = _state.value.messages.lastIndex
            // Qwen3-family models emit <think>...</think> even when the native
            // template doesn't suppress it. Buffer raw tokens, strip thinking
            // blocks, and only append the visible delta to the message.
            val rawStreamBuf = StringBuilder()
            var visibleEmitted = 0
            val tokenCb = object : LlamaBridge.TokenCallback {
                override fun onToken(token: String) {
                    rawStreamBuf.append(token)
                    val visible = stripThinkBlocks(rawStreamBuf.toString()).trimStart()
                    if (visible.length > visibleEmitted) {
                        val delta = visible.substring(visibleEmitted)
                        visibleEmitted = visible.length
                        val msgs = _state.value.messages.toMutableList()
                        if (streamingIdx in msgs.indices) {
                            msgs[streamingIdx] = msgs[streamingIdx].copy(
                                text = msgs[streamingIdx].text + delta
                            )
                            _state.value = _state.value.copy(messages = msgs)
                        }
                    }
                }
            }
            LlamaBridge.setTokenCallback(tokenCb)

            val response = withContext(Dispatchers.IO) {
                try {
                    if (_state.value.liteRtModelLoaded) {
                        val sb = StringBuilder()
                        // Use the already-captured snapshot — pendingImages was
                        // cleared above so the input bar clears instantly, but
                        // imagePaths still holds the files to send.
                        LiteRtBridge.generate(
                            text,
                            imagePaths = imagePaths.toList(),
                            searchBlock = searchBlock,
                        ).collect { tok ->
                            sb.append(tok)
                            val msgs = _state.value.messages.toMutableList()
                            if (streamingIdx in msgs.indices) {
                                msgs[streamingIdx] = msgs[streamingIdx].copy(text = sb.toString())
                                _state.value = _state.value.copy(messages = msgs)
                            }
                        }
                        sb.toString()
                    } else if (_state.value.npuModelLoaded) {
                        val sb = StringBuilder()
                        QuantaBridge.generate(text, searchBlock = searchBlock).collect { tok ->
                            sb.append(tok)
                            val msgs = _state.value.messages.toMutableList()
                            if (streamingIdx in msgs.indices) {
                                msgs[streamingIdx] = msgs[streamingIdx].copy(text = sb.toString())
                                _state.value = _state.value.copy(messages = msgs)
                            }
                        }
                        sb.toString()
                    } else if (imagePaths.isNotEmpty() && LlamaBridge.isVisionCapable()) {
                        val buffers = imagePaths.mapNotNull { path ->
                            encodeImageForVision(File(path))
                        }.toTypedArray()
                        if (buffers.isEmpty()) {
                            LlamaBridge.generate(prompt, maxTokens, temperature, topP, topK)
                        } else {
                            LlamaBridge.generateWithImages(
                                prompt, maxTokens, temperature, topP, topK, buffers
                            )
                        }
                    } else {
                        LlamaBridge.generate(prompt, maxTokens, temperature, topP, topK)
                    }
                } catch (e: Exception) {
                    Log.e("LlamaVM", "generate failed", e)
                    "[error: ${e.message}]"
                } finally {
                    LlamaBridge.setTokenCallback(null)
                }
            }
            // Clear images after they've been sent
            _state.value = _state.value.copy(pendingImages = emptyList())

            val finalMsgs = _state.value.messages.toMutableList()
            if (streamingIdx in finalMsgs.indices) {
                finalMsgs[streamingIdx] = finalMsgs[streamingIdx].copy(
                    text = stripThinkBlocks(response)
                )
            }
            _state.value = _state.value.copy(
                messages = finalMsgs,
                isGenerating = false
            )
        }
    }


    /**
     * Removes every complete <think>...</think> block and truncates at any
     * unterminated <think>. No-op when the text contains no thinking markers,
     * so vision output and plain text pass through unchanged.
     */
    private fun stripThinkBlocks(s: String): String {
        val complete = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        var out = complete.replace(s, "")
        val openIdx = out.indexOf("<think>")
        if (openIdx >= 0) out = out.substring(0, openIdx)
        return out
    }

    fun clear() {
        newConversation()
    }

    fun setStatus(text: String) {
        _state.value = _state.value.copy(status = text)
    }

    fun setError(text: String) {
        _state.value = _state.value.copy(error = text)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * DEBUG: dumps the final prompt to a world-readable file so we can
     * inspect exactly what the model receives. Remove before release.
     */
    private fun debugDumpPrompt(label: String, text: String) {
        try {
            val ctx = getApplication<Application>()
            // App-private copy (readable via `run-as`)
            java.io.File(ctx.filesDir, "last-prompt.txt").writeText(text)
            // /sdcard copy (readable directly from Termux, if permission granted)
            try {
                val dir = java.io.File("/sdcard/Download/LocalAI-App")
                dir.mkdirs()
                java.io.File(dir, "last-prompt.txt").writeText(text)
            } catch (_: Throwable) {}
            Log.i("LlamaVM", "debugDumpPrompt($label): " + text.length + " chars")
        } catch (t: Throwable) {
            Log.e("LlamaVM", "debugDumpPrompt failed", t)
        }
    }

    private fun buildPrompt(messages: List<ChatMessage>, searchBlock: String = ""): String {
        // Ask the loaded model to format the conversation using its own
        // chat template (read from GGUF metadata). Works for any model:
        // Qwen, Llama, Mistral, Gemma, Phi, etc.
        // Get the correct media marker for the loaded mmproj (Gemma uses
        // <start_of_image>, Qwen uses <__media__>, etc). If no mmproj,
        // the call returns "" and we fall back to a harmless no-op.
        val marker = try { LlamaBridge.getMediaMarker() } catch (_: Throwable) { "" }

        val lastUserIdx = messages.indexOfLast { it.isUser }
        val rolesList = mutableListOf<String>()
        val contentsList = mutableListOf<String>()
        // If we have search results, prepend a SYSTEM message. ChatML
        // (Qwen2.5) treats system turns as authoritative behavioral
        // instructions, which overrides the model's default "2023" refusal.
        if (searchBlock.isNotEmpty()) {
            rolesList.add("system")
            contentsList.add(SEARCH_SYSTEM_PROMPT + "\n\n" + searchBlock)
        }
        messages.forEachIndexed { idx, m ->
            rolesList.add(if (m.isUser) "user" else "assistant")
            var text = m.text
            if (m.isUser && m.imagePath != null && marker.isNotEmpty()) {
                text = "$marker\n$text"
            }
            // RAG v2: search data lives in the USER turn as clearly-
            // labeled context. This is the pattern models actually read.
            if (idx == lastUserIdx && searchBlock.isNotEmpty()) {
                text = "Web search results:\n" + searchBlock +
                       "\n\nQuestion: " + text
            }
            contentsList.add(text)
        }
        val roles = rolesList.toTypedArray()
        val contents = contentsList.toTypedArray()

        val formatted = try {
            LlamaBridge.applyChatTemplate(roles, contents, true)
        } catch (e: Exception) {
            Log.e("LlamaVM", "applyChatTemplate failed", e)
            ""
        }

        if (formatted.isNotEmpty()) {
            debugDumpPrompt("main", formatted)
            return formatted
        }

        // Fallback: chatml format (works for Qwen and most modern models)
        Log.w("LlamaVM", "template empty — falling back to chatml")
        val sb = StringBuilder()
        if (searchBlock.isNotEmpty()) {
            sb.append("<|im_start|>system\n")
              .append(SEARCH_SYSTEM_PROMPT).append("\n\n").append(searchBlock)
              .append("<|im_end|>\n")
        }
        messages.forEachIndexed { idx, m ->
            val role = if (m.isUser) "user" else "assistant"
            val text = if (idx == lastUserIdx && searchBlock.isNotEmpty())
                "Web search results:\n" + searchBlock + "\n\nQuestion: " + m.text
                else m.text
            sb.append("<|im_start|>").append(role).append("\n")
              .append(text).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>assistant\n")
        val fallback = sb.toString()
        debugDumpPrompt("fallback", fallback)
        return fallback
    }

    private fun queryDisplayName(uri: Uri): String? {
        val ctx = getApplication<Application>()
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        try { LlamaBridge.freeMmproj() } catch (_: Exception) {}
        try { LlamaBridge.freeModel() } catch (_: Exception) {}
    }
}
