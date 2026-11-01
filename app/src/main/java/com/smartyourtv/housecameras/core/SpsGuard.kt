package com.smartyourtv.housecameras.core

import androidx.media3.container.NalUnitUtil
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/** Crash containment for the Media3 H.265 SPS parser (v1.0.4).
 *
 *  Field crash (W2, 2026-11-01): Media3 1.4.1 died with an uncaught
 *  ArrayIndexOutOfBoundsException inside NalUnitUtil's H.265 SPS
 *  parsing, on the RTSP thread during DESCRIBE — one camera's legal
 *  but unusual SPS took the WHOLE app down within a second of launch.
 *
 *  This guard runs BEFORE any player starts: it fetches the stream's
 *  SDP itself, extracts the H.265 VPS/SPS from the fmtp line, and runs
 *  the very parser Media3 will run — on our own thread, inside a
 *  try/catch. If the parser throws, the camera becomes an error tile
 *  and no player is ever started for it. Other cameras are unaffected.
 *  If the check can't be completed (network, no fmtp, not H.265), the
 *  caller proceeds normally — the guard only vetoes a proven-bad SPS. */
object SpsGuard {

    data class SdpParams(val codec: String, val vpsB64: String?, val spsB64: String?)

    /** Parse an SDP body: find the video media section's codec + fmtp. */
    fun parseSdp(sdp: String): SdpParams? {
        var inVideo = false
        var codec = ""
        var fmtp = ""
        var payload = ""
        for (raw in sdp.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("m=video") -> { inVideo = true; payload = line.split(" ").getOrNull(3) ?: "" }
                line.startsWith("m=") -> inVideo = false
                inVideo && line.startsWith("a=rtpmap:$payload ") ->
                    codec = line.substringAfter(" ").substringBefore("/").uppercase()
                inVideo && line.startsWith("a=fmtp:$payload ") ->
                    fmtp = line.substringAfter(" ")
            }
        }
        if (codec.isEmpty()) return null
        fun param(name: String): String? {
            val m = Regex("""$name=([A-Za-z0-9+/=]+)""").find(fmtp) ?: return null
            return m.groupValues[1]
        }
        // HiP2P-family fmtp uses sprop-vps/sps/pps; RFC form uses sps=.
        return SdpParams(codec, param("sprop-vps") ?: param("vps"),
            param("sprop-sps") ?: param("sps"))
    }

    /** True = the parser handled this SPS (or there was nothing to
     *  check). False = the parser THREW: this stream must not be
     *  handed to the player.
     *
     *  This replicates RtspMediaTrack's EXACT call (verified against
     *  the 1.4.1 and 1.8.1 sources): the fmtp value is Base64-decoded,
     *  a 4-byte start code is PREPENDED (even if the camera's value
     *  already carries one — house-4's does, which is how its 37-byte
     *  SPS becomes the 41-byte array in the crash), and the parser is
     *  called as parseH265SpsNalUnit(data, offset=4, length, vps=null).
     *  Verified 2026-11-01 against house-4's real SPS: this call throws
     *  ArrayIndexOutOfBounds(41) on Media3 1.4.1, 1.8.1, 1.9.4 AND
     *  1.11.1 — no released Media3 parses it — while a standard x265
     *  SPS parses clean. The guard's veto is the fix for that stream. */
    fun h265SpsParses(vpsB64: String?, spsB64: String?): Boolean {
        if (spsB64.isNullOrEmpty()) return true
        return try {
            val decoded = Base64.getDecoder().decode(spsB64)
            val initData = ByteArray(decoded.size + 4)
            initData[3] = 1
            System.arraycopy(decoded, 0, initData, 4, decoded.size)
            NalUnitUtil.parseH265SpsNalUnit(initData, 4, initData.size, null)
            true
        } catch (_: Throwable) { false }
    }

    /** Full RTSP DESCRIBE (Basic + Digest auth) returning the SDP body,
     *  or null if it can't be fetched. One ordinary probe — the same
     *  request the player itself would make. */
    fun describeSdp(host: String, port: Int, path: String,
                    username: String, password: String): String? {
        fun attempt(authHeader: String?): Pair<Int, String>? {
            return try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(host, port), 1500); s.soTimeout = 2500
                    val url = "rtsp://$host:$port$path"
                    val req = StringBuilder("DESCRIBE $url RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n")
                    if (authHeader != null) req.append("Authorization: $authHeader\r\n")
                    req.append("\r\n")
                    s.getOutputStream().write(req.toString().toByteArray())
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val statusLine = reader.readLine() ?: return null
                    val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: return null
                    var body = ""
                    val headers = StringBuilder()
                    var line: String?
                    var contentLength = 0
                    while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                        headers.append(line).append("\n")
                        if (line!!.lowercase().startsWith("content-length:"))
                            contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
                        if (line!!.lowercase().startsWith("www-authenticate:"))
                            body = line!! // stash challenge in body slot for the caller below
                    }
                    if (code == 401) return code to body
                    if (code != 200) return code to ""
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = reader.read(buf, read, contentLength - read)
                        if (n < 0) break; read += n
                    }
                    code to String(buf, 0, read)
                }
            } catch (_: Exception) { null }
        }
        val first = attempt(null) ?: return null
        if (first.first == 200) return first.second
        if (first.first != 401 || username.isEmpty()) return null
        val challenge = first.second
        val auth = buildAuth(challenge, "DESCRIBE", "rtsp://$host:$port$path", username, password)
            ?: return null
        val second = attempt(auth) ?: return null
        return if (second.first == 200) second.second else null
    }

    private fun buildAuth(challenge: String, method: String, uri: String,
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
                val ha1 = md5("$username:$realm:$password")
                val ha2 = md5("$method:$uri")
                if (qop.contains("auth")) {
                    val nc = "00000001"; val cnonce = "hc104"
                    val resp = md5("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
                    "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", " +
                    "qop=auth, nc=$nc, cnonce=\"$cnonce\", response=\"$resp\""
                } else {
                    val resp = md5("$ha1:$nonce:$ha2")
                    "Digest username=\"$username\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\""
                }
            }
            lower.contains("basic") -> "Basic " + Base64.getEncoder()
                .encodeToString("$username:$password".toByteArray())
            else -> null
        }
    }

    /** The one-call check the player uses. Returns false ONLY when the
     *  stream is H.265 and its SPS provably breaks the parser. */
    fun streamIsSafe(cam: Camera, source: CameraSource, relay: RelayConfig): Boolean {
        val sdp = if (relay.active()) {
            describeSdp(relay.host, relay.port, "/" + cam.name.lowercase().replace(" ", "-"),
                RelayConfig.USERNAME, relay.sessionToken)
        } else {
            if (cam.host.isEmpty()) return true
            describeSdp(cam.host, source.port, source.path.ifEmpty { "/" },
                cam.credentials.username, cam.credentials.password)
        } ?: return true
        val params = parseSdp(sdp) ?: return true
        if (params.codec != "H265") return true
        return h265SpsParses(params.vpsB64, params.spsB64)
    }
}
