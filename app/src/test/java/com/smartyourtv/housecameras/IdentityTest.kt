package com.smartyourtv.housecameras

import com.smartyourtv.housecameras.core.Identity
import com.smartyourtv.housecameras.core.IdentityMatch
import org.junit.Assert.*
import org.junit.Test

class IdentityTest {
    private val id = Identity(
        mac = "7ca7b036effc",
        macAliases = mutableListOf("1ad6c736effc"),   // presented-prefix variant
        onvifUuid = "urn:uuid:aaaa", hip2pUid = "UID123",
        fingerprint = "HipCam|Basic realm=\"index.html\"")

    @Test fun macNormalization() {
        assertEquals("7ca7b036effc", IdentityMatch.normMac("7C:A7:B0:36:EF:FC"))
        assertEquals("36effc", IdentityMatch.macTail("7ca7b036effc"))
    }
    @Test fun exactMacIsStrong() {
        assertEquals(3, IdentityMatch.score(id, IdentityMatch.Evidence(mac = "7c:a7:b0:36:ef:fc")))
    }
    @Test fun presentedAliasIsStrong() {
        assertEquals(3, IdentityMatch.score(id, IdentityMatch.Evidence(mac = "1ad6c736effc")))
    }
    @Test fun uuidAndUidAreStrong() {
        assertEquals(3, IdentityMatch.score(id, IdentityMatch.Evidence(uuid = "urn:uuid:aaaa")))
        assertEquals(3, IdentityMatch.score(id, IdentityMatch.Evidence(uid = "UID123")))
    }
    @Test fun tailPlusFamilyScores2() {
        assertEquals(2, IdentityMatch.score(id, IdentityMatch.Evidence(
            mac = "00112236effc", family = "hip2p"), "hip2p"))
        // same tail, WRONG family: not evidence for this camera
        assertEquals(0, IdentityMatch.score(id, IdentityMatch.Evidence(
            mac = "00112236effc", family = "reolink"), "hip2p"))
    }
    @Test fun fingerprintPlusFamilyScores2() {
        assertEquals(2, IdentityMatch.score(id, IdentityMatch.Evidence(
            fingerprint = "HipCam|Basic realm=\"index.html\"", family = "hip2p"), "hip2p"))
    }
    @Test fun familyAloneScores1AndStrangerScores0() {
        assertEquals(1, IdentityMatch.score(id, IdentityMatch.Evidence(family = "hip2p"), "hip2p"))
        assertEquals(0, IdentityMatch.score(id, IdentityMatch.Evidence(family = "reolink"), "hip2p"))
    }
    @Test fun selectAdoptsUniqueTop() {
        val d = IdentityMatch.select(id, listOf(
            "192.168.40.50" to IdentityMatch.Evidence(family = "hip2p"),
            "192.168.40.87" to IdentityMatch.Evidence(fingerprint = id.fingerprint, family = "hip2p")), "hip2p")
        assertTrue(d is IdentityMatch.Decision.Adopt)
        assertEquals("192.168.40.87", (d as IdentityMatch.Decision.Adopt).host)
    }
    @Test fun selectRefusesTie() {
        val ev = IdentityMatch.Evidence(fingerprint = id.fingerprint, family = "hip2p")
        val d = IdentityMatch.select(id, listOf("192.168.40.87" to ev, "192.168.40.88" to ev), "hip2p")
        assertTrue(d is IdentityMatch.Decision.Ambiguous)
    }
    @Test fun selectNoneBelowThreshold() {
        val d = IdentityMatch.select(id, listOf(
            "192.168.40.87" to IdentityMatch.Evidence(family = "hip2p")), "hip2p")
        assertEquals(IdentityMatch.Decision.None, d)
    }
}
