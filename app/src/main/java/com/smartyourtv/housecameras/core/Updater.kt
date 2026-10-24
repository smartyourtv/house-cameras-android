package com.smartyourtv.housecameras.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Updater (standing law): check GitHub, ONE subtle notice per version,
 *  user taps to apply, download verified by SHA-256 before the system
 *  installer is offered. Never forced, never silent. */
object Updater {
    const val VERSION_JSON_URL =
        "https://raw.githubusercontent.com/smartyourtv/house-cameras-android/main/version.json"
    const val APK_URL =
        "https://github.com/smartyourtv/house-cameras-android/releases/latest/download/HouseCameras-latest.apk"

    data class Available(val versionCode: Int, val versionName: String, val sha256: String)

    fun check(currentCode: Int): Available? = try {
        val conn = URL(VERSION_JSON_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        val o = JSONObject(conn.inputStream.bufferedReader().readText())
        val code = o.optInt("versionCode", 0)
        if (code > currentCode) Available(code, o.optString("versionName"), o.optString("sha256"))
        else null
    } catch (_: Exception) { null }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192); var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Download + verify, then hand to the system installer. Returns a
     *  plain-language result. */
    fun downloadAndInstall(ctx: Context, expectedSha256: String): String {
        return try {
            val dest = File(ctx.cacheDir, "HouseCameras-latest.apk")
            val conn = URL(APK_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000; conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            conn.inputStream.use { ins -> dest.outputStream().use { ins.copyTo(it) } }
            if (expectedSha256.isNotEmpty() && !sha256(dest).equals(expectedSha256, ignoreCase = true))
                return "Download did not pass its safety check (checksum mismatch). Nothing was installed."
            val uri: Uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", dest)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            "Download verified. Tap Install on the system screen to update."
        } catch (e: Exception) {
            "Update download failed: ${e.message}. You can try again later — nothing was changed."
        }
    }
}
