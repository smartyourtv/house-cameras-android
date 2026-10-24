package com.smartyourtv.housecameras

import com.smartyourtv.housecameras.core.*
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun templatesMatchWindowsSibling() {
        assertEquals("/11", Templates.forFamily("hip2p").main.path)
        assertEquals("/12", Templates.forFamily("hip2p").sub.path)
        assertEquals("/h264Preview_01_main", Templates.forFamily("reolink").main.path)
        assertEquals("/Streaming/Channels/101", Templates.forFamily("hikvision").main.path)
        assertEquals("/cam/realmonitor?channel=1&subtype=0", Templates.forFamily("dahua").main.path)
        assertEquals("/stream1", Templates.forFamily("tapo").main.path)
        assertEquals(8, Templates.ALL.size)
    }

    @Test fun identifyFamilyFromBanner() {
        assertEquals("reolink", Templates.identifyFamily("Server: Reolink"))
        assertEquals("hip2p", Templates.identifyFamily("hichip camera"))
        assertNull(Templates.identifyFamily("some random server"))
    }

    @Test fun pathLibraryHasFleetPathsFirst() {
        assertEquals("/11", PathLibrary.RTSP_PATHS.first())
        assertTrue(PathLibrary.SNAPSHOT_PATHS.contains("/snapshot.jpg"))
        assertTrue(PathLibrary.MJPEG_PATHS.isNotEmpty())
    }

    @Test fun redactHidesCredentials() {
        val out = redact("rtsp://admin:secret@192.168.40.25/11 password=hunter2 token=abc")
        assertFalse(out.contains("secret")); assertFalse(out.contains("hunter2")); assertFalse(out.contains("abc"))
        assertTrue(out.contains("192.168.40.25"))
    }

    @Test fun streamUrlBuiltInMemoryOnly() {
        val cam = Camera(name = "house-1", host = "192.168.40.25", family = "hip2p",
            credentials = Credentials("admin", "pw"))
        val url = cam.streamUrl(Templates.forFamily("hip2p").sub, RelayConfig())
        assertEquals("rtsp://admin:pw@192.168.40.25:554/12", url)
        // No-credential camera produces a clean URL
        val cam2 = Camera(name = "house-2", host = "192.168.40.82")
        assertEquals("rtsp://192.168.40.82:554/11",
            cam2.streamUrl(Templates.forFamily("hip2p").main, RelayConfig()))
    }

    @Test fun relayUrlUsesRelayHostAndCameraName() {
        // Confirmed relay interface (camera-relay-build-2026-10-24):
        // rtsp://scout:<session-token>@100.78.8.93:8554/house-N
        val cam = Camera(name = "house-4", host = "192.168.40.218",
            credentials = Credentials("admin", "pw"))
        val relay = RelayConfig(enabled = true, sessionToken = "tok123")
        assertEquals("100.78.8.93", relay.host)
        val url = cam.streamUrl(Templates.forFamily("hip2p").main, relay)
        assertEquals("rtsp://scout:tok123@100.78.8.93:8554/house-4", url)
        assertFalse("camera LAN address must not appear in relay mode", url.contains("192.168"))
        assertFalse("camera password must not appear in relay mode", url.contains("admin"))
        // No token yet -> no user-info; the relay will 401 and the app
        // reports "not connected", which is the resting state.
        val bare = cam.streamUrl(Templates.forFamily("hip2p").main,
            RelayConfig(enabled = true))
        assertEquals("rtsp://100.78.8.93:8554/house-4", bare)
        // HLS fallback lives on :8888 with the same slot path.
        assertEquals("http://100.78.8.93:8888/house-4/index.m3u8", cam.hlsUrl(relay))
    }

    @Test fun discoveryMergePrefersKnownFamily() {
        val a = listOf(FoundHost("10.0.0.1", "generic", "sweep"))
        val b = listOf(FoundHost("10.0.0.1", "reolink", "onvif"))
        val merged = Discovery.merge(a, b)
        assertEquals(1, merged.size); assertEquals("reolink", merged[0].family)
    }

    @Test fun updaterSha256OfEmptyFile() {
        val f = java.io.File.createTempFile("hc-test", ".bin")
        f.writeBytes(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Updater.sha256(f))
        f.delete()
    }
}
