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

/** Who a camera IS, independent of where it currently sits (v2).
 *  The host address is a cache; this block is the truth. Fields are
 *  filled from whatever the protocols actually provide — for this
 *  fleet the workhorse anchor is the banner fingerprint + family
 *  (VSCP is best-effort, ONVIF thin); MAC/UUID/UID matching is live
 *  for any device that offers them. No credentials live here. */
data class Identity(
    var mac: String = "",                       // normalized: lowercase hex, no separators
    var macAliases: MutableList<String> = mutableListOf(),  // presented-MAC variants
    var onvifUuid: String = "",
    var onvifSerial: String = "",
    var hip2pUid: String = "",
    var fingerprint: String = "",               // RTSP Server + HTTP Server/realm evidence
    var lastVerifiedAt: Long = 0
) {
    fun anchorDescription(): String = when {
        mac.isNotEmpty() -> "MAC …${mac.takeLast(6)}"
        onvifUuid.isNotEmpty() -> "ONVIF id"
        hip2pUid.isNotEmpty() -> "camera UID"
        fingerprint.isNotEmpty() -> "banner fingerprint"
        else -> "not learned yet"
    }
}

data class Camera(
    var name: String = "",
    var host: String = "",
    var family: String = "generic",
    var enabled: Boolean = true,
    var credentials: Credentials = Credentials(),
    var main: CameraSource = CameraSource(),
    var sub: CameraSource = CameraSource(),
    var notes: String = "",
    var identity: Identity = Identity()
) {
    /** Build a playable URL in memory. Relay mode (confirmed interface,
     *  camera-relay-build-2026-10-24): the relay on the ThinkPad's
     *  Tailscale address serves each camera's MAIN stream at
     *  /<slot-name> on RTSP :8554, authenticated as user "scout" with
     *  the per-session token as the password, embedded in the URI
     *  (verified against the relay). The camera's own address and
     *  password never appear in a relay URL. */
    fun streamUrl(source: CameraSource, relay: RelayConfig): String {
        if (relay.active()) {
            val p = "/" + name.lowercase().replace(" ", "-")
            val userInfo = if (relay.sessionToken.isNotEmpty())
                "scout:${urlEncode(relay.sessionToken)}@" else ""
            return "rtsp://$userInfo${relay.host}:${relay.port}$p"
        }
        val userInfo = if (credentials.hasLogin())
            "${urlEncode(credentials.username)}:${urlEncode(credentials.password)}@" else ""
        val scheme = source.scheme.ifEmpty { "rtsp" }
        val port = if (source.port > 0) source.port else if (scheme == "http") 80 else 554
        return "$scheme://$userInfo$host:$port${source.path}"
    }
    /** HLS fallback URL on the relay (:8888, /<slot>/index.m3u8).
     *  HLS on this relay needs HTTP Basic (scout + token) AND cookie
     *  handling — see the build record. RTSP is the primary path. */
    fun hlsUrl(relay: RelayConfig): String =
        "http://${relay.host}:${relay.httpPort}/${name.lowercase().replace(" ", "-")}/index.m3u8"

    fun snapshotUrl(path: String, relay: RelayConfig): String {
        // The relay serves no snapshots; snapshot sources are LAN-only.
        return "http://$host$path" // caller adds Basic auth header; credentials never in the URL
    }
    companion object {
        fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

data class RelayConfig(
    var enabled: Boolean = false,          // Away mode on/off
    var host: String = "100.78.8.93",      // ThinkPad Tailscale address (relay binds ONLY there)
    var port: Int = 8554,                  // relay RTSP (confirmed)
    var httpPort: Int = 8888,              // relay HLS (confirmed)
    var sessionToken: String = ""          // per-session token = password for user "scout";
                                           // fresh at every session start, entered by the user,
                                           // Keystore-encrypted at rest, dies with the session
) {
    fun active() = enabled && host.isNotEmpty()
    companion object { const val USERNAME = "scout" }
}

/** The van DVR's published feed (v2 P2): the laptop publishes the
 *  DVR channels on its LAN camera relay; the app consumes them.
 *  There is deliberately NO CMSV7 anything here — no session, no
 *  account credentials on-device (owner ruling 2026-11-02). */
data class DvrConfig(
    var host: String = "192.168.40.189",   // laptop LAN address (same relay that serves house-4's transcode)
    var port: Int = 8554,                  // mediamtx RTSP default; confirm with the Laptop lane when the leg is revived
    var channels: Int = 4
) {
    fun path(channel: Int) = "/dvr-${channel + 1}"
}

data class AppConfig(
    var cameras: MutableList<Camera> = mutableListOf(),
    var relay: RelayConfig = RelayConfig(),
    var lastNotifiedVersionCode: Int = 0,
    /** Owner ruling 2026-11-02: "Steady picture" — ON = deep buffer
     *  (grid ~10 s, single ~4 s) to ride out a shaky road; OFF =
     *  lowest latency. Default ON. Read at stream start. */
    var steadyPicture: Boolean = true,
    /** Stage view last shown: HOUSE | DVR | ALL (v2 P2). */
    var homeView: String = "HOUSE",
    /** Boot-straight-to-cameras on TV boxes (owner ruling: receiver
     *  first; default ON — the receiver itself checks the device is
     *  a TV, so phones never boot-launch). */
    var bootToCameras: Boolean = true,
    var dvr: DvrConfig = DvrConfig()
)

/** Redact anything that looks like a credential-bearing URL before it
 *  reaches a log line or a crash report. */
fun redact(text: String): String =
    text.replace(Regex("""(rtsp|http|https)://[^/@\s]+@"""), "$1://***@")
        .replace(Regex("""(?i)(password|token)=[^&\s]+"""), "$1=***")
