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

    /** Fleet-generic banner tokens, recomputed on every resolveAll
     *  pass (§1.4(4)) and held here as the fallback for callers that
     *  cannot load the fleet; single-camera resolves compute their
     *  own set from the stored config first. Generic tokens can
     *  never prove identity. */
    @Volatile var genericCache: Set<String> = emptySet()
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
    fun fastPath(cam: Camera, generic: Set<String> = genericCache): FastPath {
        if (cam.host.isEmpty()) return FastPath.DEAD
        // Pack K L10: UNKNOWN used to be unreachable dead code. It
        // now means what the self-test always said it meant — the
        // check itself could not be completed (the probe threw).
        val (describeOk, fp) = try { probe(cam, cam.host) }
            catch (_: Exception) { return FastPath.UNKNOWN }
        val stored = cam.identity.fingerprint
        // STRANGER requires DISCRIMINATING disagreement: both sides
        // hold device-specific tokens and they share none. No
        // discriminating tokens on a side = no judgment from the
        // fingerprint at all (a sibling at this address is then
        // indistinguishable — the documented fleet limit).
        val storedDisc = IdentityMatch.fingerprintTokens(stored) - generic
        val freshDisc = IdentityMatch.fingerprintTokens(fp) - generic
        return when {
            describeOk && (stored.isEmpty() || fp.isEmpty() ||
                storedDisc.isEmpty() || freshDisc.isEmpty() ||
                storedDisc.intersect(freshDisc).isNotEmpty()) -> FastPath.VERIFIED
            describeOk || fp.isNotEmpty() -> FastPath.STRANGER
            else -> FastPath.DEAD
        }
    }

    /** Resolve one camera. Returns a plain-words heal message when
     *  the address changed, else null. Mutates cam.host/identity on
     *  success — the CALLER saves config.
     *
     *  The generic set is a FLEET product (§1.4(4)): computed from
     *  every camera's stored fingerprint on every pass. Callers with
     *  the fleet pass it in; a lone caller gets it from the stored
     *  config; only a config that cannot be read falls back to the
     *  shared cache. (036 H2: this set used to be declared and never
     *  computed in production, so sibling banners scored as identity.) */
    fun resolveOne(ctx: Context, cam: Camera, fleet: List<Camera>? = null): String? {
        val generic: Set<String> = when {
            fleet != null -> IdentityMatch.genericTokens(
                fleet.map { it.identity.fingerprint })
            else -> try {
                IdentityMatch.genericTokens(
                    Store.load(ctx).cameras.map { it.identity.fingerprint })
            } catch (_: Exception) { genericCache }
        }
        val state = LiveState.state(cam.name)
        state.host = cam.host; state.identityAnchor = cam.identity.anchorDescription()
        when (fastPath(cam, generic)) {
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
        if (subnet.isEmpty())
            Reports.event(ctx, "SWEEP: ${cam.name} — the phone's network couldn't be read; local sweep skipped (broadcast finds still apply)")
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
            // Hard identifiers ride along (036 M7: sweep evidence used
            // to carry none, so the score-3 paths could never fire in
            // production). The serial comes from the discovery reply
            // when it labelled one, else from a unicast ONVIF read at
            // the candidate — the fleet's working LAN anchor.
            val serial = fh.serial.ifEmpty {
                try { Ingest.onvifDeviceSerial(ghost) } catch (_: Exception) { "" }
            }
            candidates.add(fh.host to IdentityMatch.Evidence(
                mac = fh.mac, uuid = fh.uuid, uid = fh.uid,
                serial = serial,
                fingerprint = fingerprint(fh.host), family = cam.family))
        }
        if (candidates.isNotEmpty())
            Reports.event(ctx, "SWEEP: ${cam.name} — ${candidates.size} candidate(s): " +
                candidates.joinToString(", ") { "${it.first} (score ${IdentityMatch.score(cam.identity, it.second, cam.family, generic)})" })
        return when (val d = IdentityMatch.select(cam.identity, candidates, cam.family, generic)) {
            is IdentityMatch.Decision.Adopt -> {
                // §1.4(3): identity match PLUS a pulled picture. The
                // match alone never moves the record — a frame must
                // actually decode at the new address (the family's
                // snapshot is a real picture; families without one
                // keep the ladder's DESCRIBE standard, and the event
                // log says which grade proved it).
                val ghost = cam.copy(host = d.host)
                val tpl = Templates.forFamily(cam.family)
                val framed = tpl.snapshot.isEmpty() ||
                    (try { Ingest.fetchSnapshot(ghost, tpl.snapshot) }
                     catch (_: Exception) { false })
                if (!framed) {
                    Reports.event(ctx, "IDENTITY: ${cam.name} — match at ${d.host} " +
                        "could not pull a picture there; not adopting. " +
                        "A match alone is not proof.")
                    null
                } else {
                    val old = cam.host
                    cam.host = d.host
                    com.smartyourtv.housecameras.core.LiveState.state(cam.name).identityNote = ""
                    // Future-proofing amendment: a proven camera's
                    // record learns what the camera says about
                    // ITSELF — capabilities by interrogation, never
                    // assumed from the family. Its events come from
                    // frame analysis until native events feed the bus.
                    try {
                        Ingest.onvifCapabilities(cam)?.let { cam.capabilities = it }
                    } catch (_: Exception) {}
                    if (cam.eventSource.isEmpty()) cam.eventSource = "frame"
                    val fp = fingerprint(d.host)
                    if (fp.isNotEmpty()) cam.identity.fingerprint = fp
                    // Learn the hard identity the winning evidence
                    // carried — filled only from proof, only into
                    // empty fields, so the next pass anchors harder.
                    val winEv = candidates.firstOrNull { it.first == d.host }?.second
                    if (winEv != null) {
                        if (cam.identity.mac.isEmpty() && winEv.mac.isNotEmpty())
                            cam.identity.mac = IdentityMatch.normMac(winEv.mac)
                        if (cam.identity.onvifSerial.isEmpty() && winEv.serial.isNotEmpty())
                            cam.identity.onvifSerial = winEv.serial
                        if (cam.identity.onvifUuid.isEmpty() && winEv.uuid.isNotEmpty())
                            cam.identity.onvifUuid = winEv.uuid
                        if (cam.identity.hip2pUid.isEmpty() && winEv.uid.isNotEmpty())
                            cam.identity.hip2pUid = winEv.uid
                    }
                    cam.identity.lastVerifiedAt = System.currentTimeMillis()
                    state.host = d.host
                    val msg = "${cam.name} moved $old → ${d.host} — identity proven " +
                              "(${cam.identity.anchorDescription()}, match ${d.score})"
                    Reports.event(ctx, "HEAL: $msg")
                    msg
                }
            }
            is IdentityMatch.Decision.Ambiguous -> {
                // Pack I L4: ambiguity is a TILE message, not only
                // an event-log line — and it stays in Diagnostics.
                val note = "two possible matches (${d.hosts.joinToString(", ")}) — " +
                    "not guessing; check Diagnostics"
                com.smartyourtv.housecameras.core.LiveState.state(cam.name).identityNote = note
                Reports.event(ctx, "IDENTITY: ${cam.name} — $note; tile left as-is")
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
        // The generic set is recomputed on EVERY pass from the whole
        // fleet's stored fingerprints (§1.4(4)) — 036 H2's cure: the
        // field existed and was never populated in production.
        genericCache = IdentityMatch.genericTokens(
            cfg.cameras.map { it.identity.fingerprint })
        val before = cams.map { it.host }
        val pool = Executors.newFixedThreadPool(minOf(4, cams.size))
        val futures = cams.map { cam -> pool.submit { try { resolveOne(ctx, cam, cfg.cameras) } catch (_: Exception) {} } }
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
