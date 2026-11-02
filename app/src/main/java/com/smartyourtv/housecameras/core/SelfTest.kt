package com.smartyourtv.housecameras.core

import android.content.Context
import java.net.DatagramSocket
import java.net.MulticastSocket

/** One-tap self-test (standing law): exercises config health/repair,
 *  discovery sockets, and the decode stack's presence — no live camera
 *  needed. Reports plain-language pass/fail per check. */
object SelfTest {
    data class Result(val name: String, val ok: Boolean, val detail: String)

    fun run(ctx: Context): List<Result> {
        val out = mutableListOf<Result>()
        // Config load + repair state
        try {
            val cfg = Store.load(ctx)
            out.add(Result("Settings file", true,
                "${cfg.cameras.size} cameras loaded" +
                    if (Store.repairLog.isEmpty()) "" else "; repairs: ${Store.repairLog.joinToString("; ")}"))
        } catch (e: Exception) {
            out.add(Result("Settings file", false, "Could not load: ${e.message}"))
        }
        // Credential encryption round-trip
        try {
            val probe = "selftest-probe"
            val ok = Crypto.unprotect(Crypto.protect(probe)) == probe
            out.add(Result("Credential encryption (Android Keystore)", ok,
                if (ok) "Encrypt/decrypt round-trip OK" else "Round-trip mismatch"))
        } catch (e: Exception) {
            out.add(Result("Credential encryption (Android Keystore)", false, "${e.message}"))
        }
        // Discovery sockets
        try {
            DatagramSocket(0).close(); MulticastSocket(null as java.net.SocketAddress?).apply {
                bind(java.net.InetSocketAddress(0)); close() }
            out.add(Result("Discovery sockets (ONVIF / VSCP)", true, "UDP + multicast sockets open"))
        } catch (e: Exception) {
            out.add(Result("Discovery sockets (ONVIF / VSCP)", false, "${e.message}"))
        }
        // Decode stack
        try {
            Class.forName("androidx.media3.exoplayer.ExoPlayer")
            out.add(Result("Video decoder (H.264 / HEVC / MJPEG)", true, "Media3 ExoPlayer present"))
        } catch (e: Exception) {
            out.add(Result("Video decoder (H.264 / HEVC / MJPEG)", false, "ExoPlayer missing"))
        }
        // Template table integrity
        val missing = Templates.ALL.filter { it.key != "generic" && it.value.main.path.isEmpty() }
        out.add(Result("Camera family templates (${Templates.ALL.size})", missing.isEmpty(),
            if (missing.isEmpty()) "All families have stream paths" else "Missing paths: ${missing.keys}"))
        // Identity dry-run (v2): each turned-on camera's saved address
        // must answer AND look like the same camera (banner fingerprint).
        try {
            val cfg2 = Store.load(ctx)
            for (c in cfg2.cameras) {
                if (!c.enabled || c.host.isEmpty()) continue
                val fp = Resolver.fastPath(c)
                out.add(Result("Identity — ${c.name}", fp == Resolver.FastPath.VERIFIED,
                    when (fp) {
                        Resolver.FastPath.VERIFIED -> "answers at ${c.host} and matches (${c.identity.anchorDescription()})"
                        Resolver.FastPath.STRANGER -> "something answers at ${c.host} but it doesn't look like this camera — the app will search by identity"
                        Resolver.FastPath.DEAD -> "nothing answers at ${c.host} — the app will search by identity"
                        Resolver.FastPath.UNKNOWN -> "couldn't check"
                    }))
            }
        } catch (e: Exception) {
            out.add(Result("Identity check", false, "${e.message}"))
        }
        // Reports folder writable
        try {
            val f = java.io.File(Reports.dir(ctx), ".probe"); f.writeText("ok"); f.delete()
            out.add(Result("Crash-report folder", true, "Writable; reports also go to Downloads"))
        } catch (e: Exception) {
            out.add(Result("Crash-report folder", false, "${e.message}"))
        }
        return out
    }
}
