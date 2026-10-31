package com.localai.app.service

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Foreground service that spawns libstable_diffusion_core.so.
 * The .so is packaged in jniLibs and thus lands in nativeLibraryDir.
 * QNN runtime libs are extracted from assets/qnnlibs to filesDir/runtime_libs.
 * The process exposes an HTTP API on 127.0.0.1:8081.
 */
class SdNpuService : Service() {

    @Volatile private var process: Process? = null
    @Volatile private var stopping = false
    private lateinit var runtimeDir: File
    @Volatile private var runtimeDirReady = false

    private val dispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sd-npu-control")
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    companion object {
        private const val TAG = "SdNpuService"
        private const val EXEC_NAME = "libstable_diffusion_core.so"
        private const val RUNTIME_DIR = "runtime_libs"
        private const val RUNTIME_VERSION = "qnn_2_50_0_260828"
        private const val RUNTIME_STAMP = ".runtime_version"
        private const val NOTIF_ID = 100
        private const val CHAN_ID = "sd_npu_service"
        const val PORT = 8081

        @Volatile var lastError: String? = null
        @Volatile var isRunning: Boolean = false

        const val ACTION_START = "com.localai.app.SD_START"
        const val ACTION_STOP  = "com.localai.app.SD_STOP"

        const val EXTRA_MODEL_DIR  = "model_dir"
        const val EXTRA_BACKEND    = "backend"      // "sd15npu" or "sdxl"
        const val EXTRA_LOWRAM     = "lowram"
        const val EXTRA_WIDTH      = "width"
        const val EXTRA_HEIGHT     = "height"
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch { prepareRuntimeDir() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification("Preparing…"))

        when (intent?.action) {
            ACTION_STOP -> scope.launch {
                stopBackend()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                val modelDir = intent?.getStringExtra(EXTRA_MODEL_DIR)
                val backend  = intent?.getStringExtra(EXTRA_BACKEND) ?: "sd15npu"
                val lowram   = intent?.getBooleanExtra(EXTRA_LOWRAM, true) ?: true
                val width    = intent?.getIntExtra(EXTRA_WIDTH, 512) ?: 512
                val height   = intent?.getIntExtra(EXTRA_HEIGHT, 512) ?: 512
                if (modelDir.isNullOrEmpty()) {
                    lastError = "Missing model directory"
                    stopSelf()
                } else {
                    scope.launch { startBackend(File(modelDir), backend, lowram, width, height) }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------
    // Runtime assets (QNN libs) — mirrors Local Dream's flow
    // ---------------------------------------------------------------
    private fun prepareRuntimeDir() {
        try {
            val dir = File(filesDir, RUNTIME_DIR)
            val stamp = File(dir, RUNTIME_STAMP)
            if (dir.exists() && runCatching { stamp.readText() }.getOrNull() != RUNTIME_VERSION) {
                Log.i(TAG, "Wiping stale runtime dir")
                dir.deleteRecursively()
            }
            if (!dir.exists()) dir.mkdirs()
            runCatching { stamp.writeText(RUNTIME_VERSION) }

            assets.list("qnnlibs")?.forEach { name ->
                val target = File(dir, name)
                val assetStream = assets.open("qnnlibs/$name")
                val assetSize = assetStream.use { it.available().toLong() }
                if (!target.exists() || target.length() != assetSize) {
                    assets.open("qnnlibs/$name").use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    }
                    Log.d(TAG, "Copied $name")
                }
                target.setReadable(true, true)
                target.setExecutable(true, true)
            }
            // Safety checker (optional - skip if not in assets)
            try {
                assets.open("safety_checker.mnn").use { input ->
                    val sc = File(filesDir, "safety_checker.mnn")
                    if (!sc.exists() || sc.length() == 0L) {
                        sc.outputStream().use { out -> input.copyTo(out) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "safety_checker.mnn not found in assets, skipping.")
            }
            runtimeDir = dir
            runtimeDirReady = true
            Log.i(TAG, "Runtime dir ready: ${dir.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "prepareRuntimeDir failed", t)
            lastError = "Prepare runtime failed: ${t.message}"
        }
    }

    // ---------------------------------------------------------------
    // Process control
    // ---------------------------------------------------------------
    private fun startBackend(modelDir: File, backend: String, lowram: Boolean, width: Int, height: Int) {
        if (!runtimeDirReady) {
            lastError = "Runtime not ready"
            return
        }
        stopBackend()
        stopping = false

        val nativeDir = applicationInfo.nativeLibraryDir
        val exe = File(nativeDir, EXEC_NAME)
        if (!exe.exists()) {
            lastError = "Executable missing: ${exe.absolutePath}"
            return
        }

        val cmd = mutableListOf(
            exe.absolutePath,
            "--type", backend,
            "--model_dir", modelDir.absolutePath,
            "--lib_dir", runtimeDir.absolutePath,
            "--port", PORT.toString(),
        )
        val safetyChecker = File(filesDir, "safety_checker.mnn")
        if (safetyChecker.exists()) {
            cmd += listOf("--safety_checker", safetyChecker.absolutePath)
        }
        if (lowram && backend == "sdxl") cmd += "--lowram"

        // SD1.5 needs a patch file for any size other than 512x512
        if (backend == "sd15npu" && (width != 512 || height != 512)) {
            val patch = when {
                width == height -> File(modelDir, "$width.patch").takeIf { it.exists() }
                    ?: File(modelDir, "${width}x$height.patch").takeIf { it.exists() }
                else -> File(modelDir, "${width}x$height.patch").takeIf { it.exists() }
            }
            if (patch != null) {
                cmd += listOf("--patch", patch.absolutePath)
                Log.i(TAG, "Using patch: ${patch.name}")
            } else {
                Log.w(TAG, "No patch for ${width}x$height — model runs unpatched, output may be noise")
            }
        }

        val env = mutableMapOf<String, String>()
        env["LD_LIBRARY_PATH"] = listOf(
            runtimeDir.absolutePath,
            "/system/lib64", "/vendor/lib64", "/vendor/lib64/egl"
        ).joinToString(":")
        env["DSP_LIBRARY_PATH"] = runtimeDir.absolutePath
        env["ADSP_LIBRARY_PATH"] = listOf(
            runtimeDir.absolutePath,
            "/vendor/lib/rfsa/adsp",
            "/vendor/dsp/cdsp",
            "/dsp"
        ).joinToString(";")

        try {
            val pb = ProcessBuilder(cmd).apply {
                directory(File(nativeDir))
                redirectErrorStream(true)
                environment().putAll(env)
            }
            Log.i(TAG, "Spawn: ${cmd.joinToString(" ")}")
            val proc = pb.start()
            process = proc
            isRunning = true
            updateNotification("Running on NPU · port $PORT")
            startMonitor(proc)
        } catch (t: Throwable) {
            Log.e(TAG, "startBackend failed", t)
            lastError = "startBackend failed: ${t.message}"
            isRunning = false
            try {
                val logFile = File("/sdcard/Download/LocalAI-App/sd-npu-service.log")
                logFile.parentFile?.mkdirs()
                logFile.appendText("=== startBackend failed ===\n${t.stackTraceToString()}\n")
            } catch (_: Throwable) {}
        }
    }

    private fun startMonitor(proc: Process) {
        Thread {
            try {
                proc.inputStream.bufferedReader().use { r ->
                    var line: String?
                    while (r.readLine().also { line = it } != null) {
                        Log.i("SdNpuBackend", line ?: "")
                    }
                }
                val code = proc.waitFor()
                Log.i(TAG, "Backend exited: $code")
                if (!stopping && process === proc) {
                    lastError = "Backend exited with code $code"
                    isRunning = false
                }
            } catch (t: Throwable) {
                Log.e(TAG, "monitor failed", t)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun stopBackend() {
        stopping = true
        process?.let { proc ->
            try {
                proc.destroy()
                if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly()
            } catch (_: Throwable) {}
            process = null
        }
        isRunning = false
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.launch { stopBackend() }
    }

    // ---------------------------------------------------------------
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHAN_ID, "SD NPU", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHAN_ID)
            .setContentTitle("LocalAI · NPU")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, buildNotification(text))
        } catch (_: Throwable) {}
    }
}
