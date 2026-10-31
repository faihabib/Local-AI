package com.localai.app

import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashLogger(private val app: Application) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val report = buildString {
                appendLine("=== LocalAI JAVA CRASH ===")
                appendLine("Time: $timestamp")
                appendLine("Android: ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Thread: ${thread.name}")
                appendLine()
                appendLine(sw.toString())
                appendLine("=== END ===")
            }

            val candidates = buildList {
                app.getExternalFilesDir(null)?.let { add(File(it, "java-crash-$stamp.txt")) }
                app.getExternalFilesDir(null)?.let { add(File(it, "java-crash-latest.txt")) }
                add(File(app.filesDir, "java-crash-$stamp.txt"))
                add(File(app.filesDir, "java-crash-latest.txt"))
                add(File("/sdcard/Download/LocalAI-App/java-crash-$stamp.txt"))
                add(File("/sdcard/Download/LocalAI-App/java-crash-latest.txt"))
            }
            for (f in candidates) {
                try {
                    f.parentFile?.mkdirs()
                    f.writeText(report)
                    Log.e("LocalAI", "Java crash written to ${f.absolutePath}")
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        defaultHandler?.uncaughtException(thread, throwable)
    }

    /** Reads the previous process exit info. Catches native SIGSEGV crashes. */
    fun logPreviousExit() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return
            val exits = am.getHistoricalProcessExitReasons(app.packageName, 0, 5)
            if (exits.isEmpty()) return
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.appendLine("=== LocalAI PREVIOUS EXIT REPORT ===")
            sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            sb.appendLine()
            for (info in exits) {
                sb.appendLine("Timestamp: ${info.timestamp}")
                sb.appendLine("Reason: ${reasonToString(info.reason)}")
                sb.appendLine("Status: ${info.status}")
                sb.appendLine("Importance: ${info.importance}")
                sb.appendLine("Description: ${info.description ?: "(none)"}")
                sb.appendLine("Pss/Rss: ${info.pss} / ${info.rss}")
                try {
                    info.traceInputStream?.bufferedReader()?.use { r ->
                        sb.appendLine("--- trace ---")
                        r.lineSequence().take(200).forEach { sb.appendLine(it) }
                    }
                } catch (_: Throwable) {}
                sb.appendLine("---")
            }
            sb.appendLine("=== END ===")
            val out = buildList {
                app.getExternalFilesDir(null)?.let { add(File(it, "exit-$stamp.txt")) }
                app.getExternalFilesDir(null)?.let { add(File(it, "exit-latest.txt")) }
                add(File(app.filesDir, "exit-$stamp.txt"))
                add(File(app.filesDir, "exit-latest.txt"))
                add(File("/sdcard/Download/LocalAI-App/exit-$stamp.txt"))
                add(File("/sdcard/Download/LocalAI-App/exit-latest.txt"))
            }
            for (f in out) {
                try {
                    f.parentFile?.mkdirs()
                    f.writeText(sb.toString())
                    Log.e("LocalAI", "Previous-exit report written to ${f.absolutePath}")
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.w("LocalAI", "logPreviousExit failed", e)
        }
    }

    private fun reasonToString(r: Int): String = when (r) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (native segfault/abort)"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($r)"
    }
}
