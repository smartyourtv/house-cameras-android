package com.smartyourtv.housecameras.core

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Crash/error reports — TWO surfaces (standing law): an in-app list,
 *  and a file in Downloads written synchronously at crash time.
 *  Never auto-uploaded. Credentials are redacted before anything is
 *  written. */
object Reports {
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val human = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun dir(ctx: Context) = File(ctx.filesDir, "reports").apply { mkdirs() }

    fun write(ctx: Context, summary: String, error: Throwable?) {
        val text = buildString {
            appendLine("House Cameras — crash/error report")
            appendLine("Time: ${human.format(Date())}")
            appendLine("App: House Cameras ${com.smartyourtv.housecameras.BuildConfig.VERSION_NAME} (${com.smartyourtv.housecameras.BuildConfig.VERSION_CODE})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Summary: $summary")
            if (error != null) {
                appendLine("Exception: ${error.javaClass.name}: ${error.message}")
                appendLine(redact(error.stackTraceToString()))
            }
        }
        val name = "HouseCameras-crash-${stamp.format(Date())}.txt"
        try { File(dir(ctx), name).writeText(text) } catch (_: Exception) {}
        writeToDownloads(ctx, name, text)
    }

    private fun writeToDownloads(ctx: Context, name: String, text: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            } else {
                @Suppress("DEPRECATION")
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                File(dl, name).writeText(text)
            }
        } catch (_: Exception) { /* the in-app copy already landed */ }
    }

    /** Engine event log (v2): heals, restarts, breaker trips — the
     *  self-repair law's "every repair is logged" surface. Appended
     *  in-app only (events.log, capped by rotation at ~200 KB); the
     *  diagnostics card shows the tail. Never uploaded. */
    @Synchronized fun event(ctx: Context, line: String) {
        try {
            val f = File(dir(ctx), "events.log")
            if (f.length() > 200_000) {
                val tail = f.readText().takeLast(100_000)
                f.writeText(tail)
            }
            f.appendText("${human.format(Date())}  ${redact(line)}\n")
        } catch (_: Exception) {}
    }

    fun events(ctx: Context, last: Int = 40): List<String> = try {
        File(dir(ctx), "events.log").readLines().takeLast(last)
    } catch (_: Exception) { emptyList() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun installCrashHandler(ctx: Context) {
        val appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { write(appCtx, "Uncaught exception in ${thread.name}", error) } catch (_: Exception) {}
            // CONTAINMENT (v1.0.4): a failure inside the media library
            // on one of ITS OWN background threads (the W2 house-4
            // crash: Media3's RTSP thread died parsing an H.265 SPS)
            // must never take the whole process down. The report above
            // is already written; for a background-thread media3
            // failure we simply do not chain to the system killer.
            // The affected tile goes dark / shows its status; every
            // other camera keeps working. Main-thread failures and
            // non-media failures keep the normal crash path.
            val fromMediaLib = error.stackTraceToString().contains("androidx.media3")
            val isMainThread = thread === android.os.Looper.getMainLooper()?.thread
            if (fromMediaLib && !isMainThread) {
                android.util.Log.e("HouseCameras",
                    "Contained media-library failure on ${thread.name}: ${error.javaClass.simpleName}")
                return@setDefaultUncaughtExceptionHandler
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
