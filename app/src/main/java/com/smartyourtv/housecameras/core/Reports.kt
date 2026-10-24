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

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.name.endsWith(".txt") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun installCrashHandler(ctx: Context) {
        val appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { write(appCtx, "Uncaught exception in ${thread.name}", error) } catch (_: Exception) {}
            previous?.uncaughtException(thread, error)
        }
    }
}
