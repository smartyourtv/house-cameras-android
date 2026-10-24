package com.smartyourtv.housecameras.core

/** Credentials live in their own object, separate from the stream
 *  description (2026-10-23 audit item 2). URLs are built in memory at
 *  connect time and never stored or logged. */
data class Credentials(var username: String = "", var password: String = "") {
    fun hasLogin() = username.isNotEmpty()
}

/** The winning ingest result for a camera (what actually plays). */
data class CameraSource(
    var kind: String = "",        // rtsp | mjpeg | snapshot
    var path: String = "",        // path only — no credentials
    var port: Int = 554,
    var scheme: String = "rtsp",
    var via: String = ""          // onvif | template | generic | mjpeg | snapshot | relay
)

data class Camera(
    var name: String = "",
    var host: String = "",
    var family: String = "generic",
    var enabled: Boolean = true,
    var credentials: Credentials = Credentials(),
    var main: CameraSource = CameraSource(),
    var sub: CameraSource = CameraSource(),
    var notes: String = ""
) {
    /** Build a playable URL in memory. In relay mode the host is the
     *  ThinkPad relay (Tailscale) and the path is /<camera-name>;
     *  the session token is passed by the player as an HTTP header /
     *  RTSP user-info by the caller — never written to disk here. */
    fun streamUrl(source: CameraSource, relay: RelayConfig): String {
        if (relay.active()) {
            val p = "/" + name.lowercase().replace(" ", "-")
            return "rtsp://${relay.host}:${relay.port}$p"
        }
        val userInfo = if (credentials.hasLogin())
            "${urlEncode(credentials.username)}:${urlEncode(credentials.password)}@" else ""
        val scheme = source.scheme.ifEmpty { "rtsp" }
        val port = if (source.port > 0) source.port else if (scheme == "http") 80 else 554
        return "$scheme://$userInfo$host:$port${source.path}"
    }
    fun snapshotUrl(path: String, relay: RelayConfig): String {
        if (relay.active()) return "http://${relay.host}:${relay.httpPort}/${name.lowercase().replace(" ", "-")}/snapshot.jpg"
        val auth = credentials
        val base = "http://$host$path"
        return base // caller adds Basic auth header; credentials never in the URL
    }
    companion object {
        fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

data class RelayConfig(
    var enabled: Boolean = false,          // Away mode on/off
    var host: String = "",                 // ThinkPad Tailscale address/name
    var port: Int = 8554,                  // relay RTSP port (confirm with relay lane)
    var httpPort: Int = 8080,              // relay snapshot port (confirm with relay lane)
    var sessionToken: String = ""          // per-session token, entered by user, Keystore-encrypted at rest
) {
    fun active() = enabled && host.isNotEmpty()
}

data class AppConfig(
    var cameras: MutableList<Camera> = mutableListOf(),
    var relay: RelayConfig = RelayConfig(),
    var lastNotifiedVersionCode: Int = 0
)

/** Redact anything that looks like a credential-bearing URL before it
 *  reaches a log line or a crash report. */
fun redact(text: String): String =
    text.replace(Regex("""(rtsp|http|https)://[^/@\s]+@"""), "$1://***@")
        .replace(Regex("""(?i)(password|token)=[^&\s]+"""), "$1=***")
