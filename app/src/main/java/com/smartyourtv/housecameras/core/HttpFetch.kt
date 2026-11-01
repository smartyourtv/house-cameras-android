package com.smartyourtv.housecameras.core

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Base64

/** Minimal HTTP fetch with camera-style auth: many cameras answer
 *  401 with a Basic or Digest challenge on their snapshot endpoints
 *  (the house fleet's HTTP login is exactly that, even though its
 *  RTSP is anonymous). Retries once with the stored credentials in
 *  whichever scheme the camera demands. Credentials are only ever
 *  sent to the camera's own host, in the auth header — never in a
 *  URL, never logged. */
object HttpFetch {
    enum class Outcome { OK, NOT_FOUND, AUTH_REQUIRED, ERROR }
    data class Result(val outcome: Outcome, val bytes: ByteArray? = null)

    fun get(url: String, username: String, password: String): Result {
        val first = rawGet(url, null)
        if (first.outcome != Outcome.AUTH_REQUIRED) return first
        if (username.isEmpty()) return first   // a login is required and we hold none
        val auth = buildAuth(first.bytes?.let { String(it, Charsets.ISO_8859_1) } ?: "",
            "GET", url, username, password) ?: return first
        val second = rawGet(url, auth)
        return if (second.outcome == Outcome.AUTH_REQUIRED)
            Result(Outcome.AUTH_REQUIRED) else second
    }

    /** The WWW-Authenticate value is carried in Result.bytes on a 401. */
    private fun rawGet(url: String, authHeader: String?): Result {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 1500; conn.readTimeout = 2000
            conn.instanceFollowRedirects = false
            if (authHeader != null) conn.setRequestProperty("Authorization", authHeader)
            when (conn.responseCode) {
                200 -> Result(Outcome.OK, conn.inputStream.readBytes())
                401 -> Result(Outcome.AUTH_REQUIRED,
                    (conn.getHeaderField("WWW-Authenticate") ?: "").toByteArray(Charsets.ISO_8859_1))
                404 -> Result(Outcome.NOT_FOUND)
                in 200..299 -> Result(Outcome.OK, try { conn.inputStream.readBytes() } catch (_: Exception) { null })
                else -> Result(Outcome.ERROR)
            }
        } catch (_: Exception) { Result(Outcome.ERROR) }
    }

    fun buildAuth(challenge: String, method: String, uri: String,
                  username: String, password: String): String? {
        val lower = challenge.lowercase()
        return when {
            lower.contains("digest") -> {
                fun field(name: String): String =
                    Regex("""$name="([^"]+)"""").find(challenge)?.groupValues?.get(1)
                        ?: Regex("""$name=([^,\s]+)""").find(challenge)?.groupValues?.get(1) ?: ""
                val realm = field("realm"); val nonce = field("nonce"); val qop = field("qop")
                fun md5(s: String): String = MessageDigest.getInstance("MD5")
                    .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
                // Digest uri is the path+query only, not the full URL.
                val path = try { URL(uri).file.ifEmpty { "/" } } catch (_: Exception) { uri }
                val ha1 = md5("$username:$realm:$password")
                val ha2 = md5("$method:$path")
                if (qop.contains("auth")) {
                    val nc = "00000001"; val cnonce = "hc107"
                    val resp = md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
                    "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$path\", " +
                    "qop=auth, nc=$nc, cnonce=\"$cnonce\", response=\"$resp\""
                } else {
                    val resp = md5("$ha1:$nonce:$ha2")
                    "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$path\", response=\"$resp\""
                }
            }
            lower.contains("basic") -> "Basic " + Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray())
            else -> null
        }
    }

    /** Snapshot candidate paths: the family template first, then the
     *  paths the Mechanic lane verified answer (with a login) on the
     *  house fleet, then the generic library. */
    fun snapshotCandidates(templatePath: String): List<String> {
        val extra = listOf("/snap.jpg", "/tmpfs/auto.jpg", "/cgi-bin/snapshot.cgi",
            "/cgi-bin/media.cgi?action=getSnapshot")
        return (listOf(templatePath) + extra + PathLibrary.SNAPSHOT_PATHS)
            .filter { it.isNotEmpty() }.distinct()
    }
}
