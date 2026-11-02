package com.smartyourtv.housecameras.core

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Identity resolution (v2): the address in config is a cache; this
 *  resolver keeps it true. Fast path first — the device at the
 *  cached address must look like the same camera (banner fingerprint
 *  + a stream DESCRIBE). Only when the cached address is DEAD or a
 *  STRANGER does a sweep look for the camera elsewhere, and a
 *  repoint requires the IdentityMatch rules (unique top score >= 2)
 *  plus a successful DESCRIBE at the new address. Every heal is
 *  logged (Reports.event) and shown in diagnostics via LiveState.
 *  A resolver failure changes nothing — never worse than v1. */
object Resolver {
    enum class FastPath { VERIFIED, STRANGER, DEAD, UNKNOWN }

    /** Banner evidence for a host: RTSP Server header + HTTP
     *  Server/realm. Contains no credentials. Resolver-grade
     *  timeouts (2.5 s): this is a background pass, and the road it
     *  runs on swings to seconds of latency (rehearsal item 5). */
    fun fingerprint(host: String): String {
        val rtsp = Discovery.rtspBanner(host, timeoutMs = 2500)
        val serverLine = rtsp.lineSequence()
            .firstOrNull { it.lowercase().startsWith("server:") }
            ?.substringAfter(":")?.trim() ?: ""
        val http = Discovery.httpBanner(host, timeoutMs = 2500).trim()
        return listOf(serverLine, http).filter { it.isNotEmpty() }.joinToString("|")
    }

    private fun probe(cam: Camera, host: String): Pair<Boolean, String> {
        val ghost = cam.copy(host = host)
        val tpl = Templates.forFamily(cam.family)
        val src = if (tpl.sub.path.isNotEmpty()) tpl.sub else tpl.main
        val describeOk = try { Ingest.rtspDescribe(ghost, src) } catch (_: Exception) { false }
        return describeOk to fingerprint(host)
    }

    /** What the cached address looks like right now. PARTIAL
     *  EVIDENCE RULE (rehearsal item 5): a stream that DESCRIBEs is
     *  verified whenever the stored fingerprint is empty, the fresh
     *  one came back empty/partial, or the two OVERLAP on any token.
     *  STRANGER requires a full house: something answers, and its
     *  complete fingerprint shares NOTHING with the stored one. */
    fun fastPath(cam: Camera): FastPath {
        if (cam.host.isEmpty()) return FastPath.DEAD
        val (describeOk, fp) = probe(cam, cam.host)
        val stored = cam.identity.fingerprint
        return when {
            describeOk && (stored.isEmpty() || fp.isEmpty() ||
                IdentityMatch.fingerprintOverlap(stored, fp)) -> FastPath.VERIFIED
            describeOk || fp.isNotEmpty() -> FastPath.STRANGER
            else -> FastPath.DEAD
        }
    }

    /** Resolve one camera. Returns a plain-words heal message when
     *  the address changed, else null. Mutates cam.host/identity on
     *  success — the CALLER saves config. */
    fun resolveOne(ctx: Context, cam: Camera): String? {
        val state = LiveState.state(cam.name)
        state.host = cam.host; state.identityAnchor = cam.identity.anchorDescription()
        when (fastPath(cam)) {
            FastPath.VERIFIED -> {
                if (cam.identity.fingerprint.isEmpty()) {
                    val fp = fingerprint(cam.host)
                    if (fp.isNotEmpty()) cam.identity.fingerprint = fp
                }
                cam.identity.lastVerifiedAt = System.currentTimeMillis()
                return null
            }
            FastPath.UNKNOWN -> return null
            else -> { /* DEAD or STRANGER: sweep */ }
        }
        val subnet = Discovery.localSubnetPrefix(ctx)
        val found = Discovery.merge(
            try { Discovery.onvif(1500, ctx) } catch (_: Exception) { emptyList() },
            try { Discovery.hip2pVscp(1200) } catch (_: Exception) { emptyList() },
            try { Discovery.lanSweep(subnet) } catch (_: Exception) { emptyList() })
        Reports.event(ctx, "SWEEP: ${cam.name} — ${found.size} hosts up on the network")
        val candidates = mutableListOf<Pair<String, IdentityMatch.Evidence>>()
        for (fh in found) {
            if (fh.host == cam.host) continue
            val fam = fh.family ?: "generic"
            if (fam != "generic" && fam != cam.family) continue
            val ghost = cam.copy(host = fh.host)
            val tpl = Templates.forFamily(cam.family)
            val src = if (tpl.sub.path.isNotEmpty()) tpl.sub else tpl.main
            val ok = try { Ingest.rtspDescribe(ghost, src) } catch (_: Exception) { false }
            if (!ok) continue
            candidates.add(fh.host to IdentityMatch.Evidence(
                fingerprint = fingerprint(fh.host), family = cam.family))
        }
        if (candidates.isNotEmpty())
            Reports.event(ctx, "SWEEP: ${cam.name} — ${candidates.size} candidate(s): " +
                candidates.joinToString(", ") { "${it.first} (score ${IdentityMatch.score(cam.identity, it.second, cam.family)})" })
        return when (val d = IdentityMatch.select(cam.identity, candidates, cam.family)) {
            is IdentityMatch.Decision.Adopt -> {
                val old = cam.host
                cam.host = d.host
                val fp = fingerprint(d.host)
                if (fp.isNotEmpty()) cam.identity.fingerprint = fp
                cam.identity.lastVerifiedAt = System.currentTimeMillis()
                state.host = d.host
                val msg = "${cam.name} moved $old → ${d.host} — identity proven " +
                          "(${cam.identity.anchorDescription()}, match ${d.score})"
                Reports.event(ctx, "HEAL: $msg")
                msg
            }
            is IdentityMatch.Decision.Ambiguous -> {
                Reports.event(ctx, "IDENTITY: ${cam.name} — two possible matches " +
                    "(${d.hosts.joinToString(", ")}); not guessing, tile left as-is")
                null
            }
            IdentityMatch.Decision.None -> {
                Reports.event(ctx, "IDENTITY: ${cam.name} not found on the network; " +
                    "keeping last address ${cam.host}")
                null
            }
        }
    }

    /** Resolve every enabled camera in parallel. Returns true if any
     *  camera changed (caller saves). Never throws. */
    fun resolveAll(ctx: Context, cfg: AppConfig): Boolean {
        if (cfg.relay.active()) return false   // cameras are not on this network when away
        val cams = cfg.cameras.filter { it.enabled && it.host.isNotEmpty() }
        if (cams.isEmpty()) return false
        val before = cams.map { it.host }
        val pool = Executors.newFixedThreadPool(minOf(4, cams.size))
        val futures = cams.map { cam -> pool.submit { try { resolveOne(ctx, cam) } catch (_: Exception) {} } }
        futures.forEach { try { it.get(45, TimeUnit.SECONDS) } catch (_: Exception) {} }
        pool.shutdown()
        return cams.map { it.host } != before
    }

    fun resolveAsync(ctx: Context, cfg: AppConfig, done: (Boolean) -> Unit) {
        thread(name = "resolver") {
            val changed = try { resolveAll(ctx, cfg) } catch (_: Exception) { false }
            done(changed)
        }
    }
}
