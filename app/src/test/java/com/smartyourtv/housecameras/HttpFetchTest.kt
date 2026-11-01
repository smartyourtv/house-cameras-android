package com.smartyourtv.housecameras

import com.smartyourtv.housecameras.core.HttpFetch
import org.junit.Assert.*
import org.junit.Test

class HttpFetchTest {
    @Test fun candidatesPutTemplateFirstAndIncludeFleetPaths() {
        val c = HttpFetch.snapshotCandidates("/snapshot.jpg")
        assertEquals("/snapshot.jpg", c.first())
        assertTrue(c.contains("/snap.jpg"))
        assertTrue(c.contains("/tmpfs/auto.jpg"))
        assertTrue(c.contains("/cgi-bin/snapshot.cgi"))
        assertTrue(c.contains("/cgi-bin/media.cgi?action=getSnapshot"))
        assertEquals(c.size, c.distinct().size)
    }

    @Test fun basicAuthHeader() {
        val h = HttpFetch.buildAuth("Basic realm=\"index.html\"", "GET",
            "http://192.168.40.190/snap.jpg", "admin", "pw")!!
        assertTrue(h.startsWith("Basic "))
        assertEquals("admin:pw", String(java.util.Base64.getDecoder().decode(h.substring(6))))
    }

    @Test fun digestAuthHeader() {
        // RFC 2617 parties and nonce; the expected response is computed
        // here from the RFC formula with the client's fixed nc/cnonce.
        val challenge = "Digest realm=\"testrealm@host.com\", qop=\"auth\", " +
            "nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\""
        val h = HttpFetch.buildAuth(challenge, "GET", "http://example.com/dir/index.html",
            "Mufasa", "Circle Of Life")!!
        fun md5(s: String) = java.security.MessageDigest.getInstance("MD5")
            .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
        val ha1 = md5("Mufasa:testrealm@host.com:Circle Of Life")
        val ha2 = md5("GET:/dir/index.html")
        val expected = md5("$ha1:dcd98b7102dd2f0e8b11d0f600bfb0c093:00000001:hc107:auth:$ha2")
        assertTrue(h.contains("uri=\"/dir/index.html\""))
        assertTrue(h.contains("response=\"$expected\""))
        // HA1 itself is the RFC's published value — anchors the chain.
        assertEquals("939e7578ed9e3c518a452acee763bce9", ha1)
    }
}
