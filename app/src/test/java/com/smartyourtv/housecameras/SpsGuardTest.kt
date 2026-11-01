package com.smartyourtv.housecameras

import com.smartyourtv.housecameras.core.SpsGuard
import org.junit.Assert.*
import org.junit.Test

class SpsGuardTest {
    // A genuine H.265 VPS/SPS pair (x265, 800x448 — same frame size as
    // house-4's sub-stream), generated locally for this test.
    private val vps = "QAEMAf//AWAAAAMAkAAAAwAAAwBalZgJ"
    private val sps = "QgEBAWAAAAMAkAAAAwAAAwBaoAZCAcFllZpJMrwFoCAAAAMAIAAAAwCh"

    private val sdp = """
        v=0
        o=- 0 0 IN IP4 192.168.40.190
        s=stream
        m=video 0 RTP/AVP 96
        a=rtpmap:96 H265/90000
        a=fmtp:96 packetization-mode=1;sprop-vps=$vps;sprop-sps=$sps;sprop-pps=RAHBcrRiQA==
        a=control:trackID=0
    """.trimIndent()

    @Test fun parsesH265Sdp() {
        val p = SpsGuard.parseSdp(sdp)!!
        assertEquals("H265", p.codec)
        assertEquals(vps, p.vpsB64)
        assertEquals(sps, p.spsB64)
    }

    @Test fun validSpsParses() {
        assertTrue(SpsGuard.h265SpsParses(vps, sps))
    }

    @Test fun house4RealSpsIsVetoed() {
        // house-4's ACTUAL sprop values (anonymous DESCRIBE of
        // rtsp://192.168.40.190:554/12, pulled 2026-11-01 by the
        // Mechanic lane). This SPS crashes Media3's parser with
        // ArrayIndexOutOfBounds length=41/index=41 on 1.4.1, 1.8.1,
        // 1.9.4 and 1.11.1 (verified in a JVM harness replicating
        // RtspMediaTrack's exact call). The guard MUST veto it.
        val h4vps = "AAAAAUABDAH//wFgAAADALAAAAMAAAMAWqwJ"
        val h4sps = "AAAAAUIBAQFgAAADALAAAAMAAAMAWqAGQgHBY2u5MvzcFBgUAg=="
        assertFalse(SpsGuard.h265SpsParses(h4vps, h4sps))
    }

    @Test fun garbageSpsIsContainedNotFatal() {
        // Random bytes where an SPS should be: the guard must answer
        // "does not parse" (or accept) — but never throw out of the guard.
        val junk = java.util.Base64.getEncoder().encodeToString(ByteArray(41) { 0x7f })
        SpsGuard.h265SpsParses(null, junk) // must not throw
        // Truncations of the real SPS at every length: never throws.
        val raw = java.util.Base64.getDecoder().decode(sps)
        for (len in 1 until raw.size) {
            val part = java.util.Base64.getEncoder().encodeToString(raw.copyOf(len))
            SpsGuard.h265SpsParses(vps, part) // must not throw
        }
    }

    @Test fun h264SdpIsNotVetoed() {
        val h264 = sdp.replace("H265", "H264").replace("sprop-vps=$vps;", "")
        val p = SpsGuard.parseSdp(h264)!!
        assertEquals("H264", p.codec)
    }
}
