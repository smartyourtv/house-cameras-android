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

/** What a camera PROVED it can do, learned by asking it — never
 *  assumed from its family (future-proofing amendment, 2026-11-22,
 *  rule 1). "advertised" = the service lists itself; "proven" = a
 *  read-only call against it answered. Today's fleet advertises
 *  PTZ it does not have fitted; features gate on "proven". */
data class Capabilities(
    var services: String = "",    // comma-joined ONVIF service names, as listed
    var ptz: String = "",         // proven | advertised | absent
    var events: String = "",      // advertised | absent
    var mainCodec: String = "",   // e.g. "H264 1920x1080", from its own profiles
    var learnedAt: Long = 0,
    // Pile-2 seats (field-study additions, 2026-11-22):
    var ptzPresets: String = "",  // D2: comma-joined preset names, filled only when PTZ is proven
    var talk: String = "",        // D8: advertised | absent — its profiles carry an audio channel
    var nativeEvents: Boolean = false  // D1: proven only by a working pull-point; false on today's fleet, honestly
)

data class Camera(
    var name: String = "",
    var host: String = "",
    var family: String = "generic",
    var enabled: Boolean = true,
    var credentials: Credentials = Credentials(),
    var main: CameraSource = CameraSource(),
    var sub: CameraSource = CameraSource(),
    var notes: String = "",
    var identity: Identity = Identity(),
    var capabilities: Capabilities = Capabilities(),
    /** Behavior as data (amendment rule 2): the keep-alive method
     *  learned for this camera ("" = not learned; rtcp |
     *  set_parameter | none) and where its events come from
     *  ("" | frame | native | external). A new family is a new
     *  record filled by discovery, not a code branch. */
    var keepalive: String = "",
    var eventSource: String = "",
    // Pile-2 reserved seats (field-study additions, 2026-11-22) —
    // each ships EMPTY and honest; no UI pretends otherwise:
    var claimToken: String = "",   // S7: a factory-printed claim code, when the camera carries one
    var zones: String = "",        // D5: analysis zones as JSON polygon text ("" = none)
    var viewers: String = "household",  // D4: who may view (comma-joined identities)
    var recorder: String = "",     // D3: assigned recorder ("" = the house primary rule)
    var siteId: String = "home",   // D7/S9: the site this record belongs to
    var setupClass: String = ""    // S8: wired | battery | poe | via-recorder
) {
    /** Build a playable URL in memory. Relay mode (interface of
     *  record, camera-relay-build-2026-10-24): the relay on the
     *  ThinkPad's Tailscale address serves each camera's MAIN
     *  stream at /<slot-name> on RTSP :8554, authenticated as user
     *  "scout" with the per-session token as the password,
     *  embedded in the URI. The camera's own address and password
     *  never appear in a relay URL.
     *  STATE, proof stage 2026-11-22: that interface was verified
     *  against the relay on 2026-10-24, but the running :8554
     *  instance today is the DVR-row relay (paths /dvr-1…/dvr-4
     *  only) and REFUSES the house paths — DESCRIBE /house-1 and
     *  /house-5 answer 400. Away mode cannot stand up until a
     *  house-paths relay is restored on the ThinkPad side; the
     *  interface above remains the contract it must serve. LAN
     *  (direct) viewing is unaffected. */
    fun streamUrl(source: CameraSource, relay: RelayConfig): String {
        if (relay.active()) {
            val p = "/" + name.lowercase().replace(" ", "-")
            val userInfo = if (relay.sessionToken.isNotEmpty())
                "scout:${urlEncode(relay.sessionToken)}@" else ""
            return "rtsp://$userInfo${relay.host}:${relay.port}$p"
        }
        // Pack D: some families take the login inside the path.
        // Templates carry {user}/{password} placeholders — never a
        // baked credential — substituted here at connect time; a
        // path with its own login gets no userinfo form.
        val pathLogin = source.path.contains("{user}") ||
            source.path.contains("{password}")
        val path = if (pathLogin) source.path
            .replace("{user}", urlEncode(credentials.username))
            .replace("{password}", urlEncode(credentials.password))
            else source.path
        val userInfo = if (credentials.hasLogin() && !pathLogin)
            "${urlEncode(credentials.username)}:${urlEncode(credentials.password)}@" else ""
        val scheme = source.scheme.ifEmpty { "rtsp" }
        val port = if (source.port > 0) source.port else if (scheme == "http") 80 else 554
        return "$scheme://$userInfo$host:$port$path"
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
    // Portability (owner, Nov 22): NO house's relay address is a
    // default. A fresh install starts empty; the address is entered
    // in Settings for the house it serves. Existing installs keep
    // the address they already stored.
    var host: String = "",
    var port: Int = 8554,                  // relay RTSP — the Oct 24 interface port;
                                           // Nov 22 proof: today's :8554 instance
                                           // serves no house paths (see streamUrl)
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
    // Portability (owner, Nov 22): no house's address as a default;
    // the DVR relay address is config, entered for the house served.
    var host: String = "",
    var port: Int = 8554,                  // the ThinkPad DVR relay — live at :8554 with
                                           // paths /dvr-1…/dvr-4 (socket + DESCRIBE
                                           // confirmed, proof stage Nov 22)
    var channels: Int = 4
) {
    fun path(channel: Int) = "/dvr-${channel + 1}"
}

data class AppConfig(
    var cameras: MutableList<Camera> = mutableListOf(),
    var relay: RelayConfig = RelayConfig(),
    var lastNotifiedVersionCode: Int = 0,
    /** Pack K L9: the update notice is persistent, not a one-shot
     *  Toast — this records the version code the owner dismissed,
     *  so the indicator stays away for THAT version only. */
    var updateNoticeDismissed: Int = 0,
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
