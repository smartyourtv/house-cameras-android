package com.smartyourtv.housecameras.core

/** Identity matching (v2 core). Pure logic — unit-tested.
 *
 *  Match scores: 3 = a hard identifier is equal (MAC / ONVIF UUID /
 *  HiP2P UID / ONVIF serial). 2 = MAC tail + family, or banner
 *  fingerprint + family (the working standard the laptop registry
 *  itself used: burned-in MAC tail plus a live picture). 1 = family
 *  only. 0 = no evidence. A repoint requires a UNIQUE top score of
 *  at least 2 — a tie at the top refuses (ambiguity rule). */
object IdentityMatch {
    fun normMac(s: String): String {
        val hex = s.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        return if (hex.length > 12) hex.takeLast(12) else hex
    }
    fun macTail(normalized: String): String =
        if (normalized.length >= 6) normalized.takeLast(6) else normalized

    data class Evidence(
        val mac: String = "", val uuid: String = "", val uid: String = "",
        val serial: String = "", val fingerprint: String = "", val family: String = ""
    )

    fun score(id: Identity, ev: Evidence, camFamily: String = ""): Int {
        val evMac = normMac(ev.mac)
        if (evMac.isNotEmpty()) {
            if (id.mac.isNotEmpty() && evMac == normMac(id.mac)) return 3
            if (id.macAliases.any { normMac(it) == evMac }) return 3
        }
        if (ev.uuid.isNotEmpty() && id.onvifUuid.isNotEmpty() && ev.uuid == id.onvifUuid) return 3
        if (ev.uid.isNotEmpty() && id.hip2pUid.isNotEmpty() && ev.uid == id.hip2pUid) return 3
        if (ev.serial.isNotEmpty() && id.onvifSerial.isNotEmpty() && ev.serial == id.onvifSerial) return 3
        val familyMatches = ev.family.isNotEmpty() && ev.family == camFamily
        var best = 0
        if (evMac.isNotEmpty() && id.mac.isNotEmpty() &&
            macTail(evMac) == macTail(normMac(id.mac)) && familyMatches) best = 2
        if (ev.fingerprint.isNotEmpty() && id.fingerprint.isNotEmpty() &&
            fingerprintOverlap(id.fingerprint, ev.fingerprint) && familyMatches)
            best = maxOf(best, 2)
        if (best == 0 && familyMatches) best = 1
        return best
    }

    /** Fingerprints are token sets ("rtspServer|httpServer realm…").
     *  On a road with seconds of latency swing, any single fetch may
     *  return only part of the evidence — so ANY shared non-trivial
     *  token counts as the same camera (rehearsal item 5 fix). */
    fun fingerprintTokens(fp: String): Set<String> =
        fp.split('|').map { it.trim() }.filter { it.length >= 3 }.toSet()
    fun fingerprintOverlap(a: String, b: String): Boolean =
        fingerprintTokens(a).intersect(fingerprintTokens(b)).isNotEmpty()

    sealed class Decision {
        data class Adopt(val host: String, val score: Int) : Decision()
        data class Ambiguous(val hosts: List<String>) : Decision()
        object None : Decision()
    }

    fun select(id: Identity, candidates: List<Pair<String, Evidence>>, camFamily: String = ""): Decision {
        val scored = candidates.map { (host, ev) -> host to score(id, ev, camFamily) }
            .filter { it.second >= 2 }
        if (scored.isEmpty()) return Decision.None
        val top = scored.maxOf { it.second }
        val winners = scored.filter { it.second == top }.map { it.first }.distinct()
        return if (winners.size == 1) Decision.Adopt(winners[0], top)
               else Decision.Ambiguous(winners)
    }
}
