package com.smartyourtv.housecameras

import com.smartyourtv.housecameras.core.*
import org.junit.Assert.*
import org.junit.Test

/** 0b Finding 1 (2026-11-22): a save that cannot protect the
 *  credentials it carries must REFUSE in plain words — never
 *  crash, never write a half-protected config. These tests drive
 *  Store.protectedCopy, the pure seam the save path runs through,
 *  with stand-in protectors (the real one is the Android
 *  Keystore, which no JVM test can hold). */
class ProtectionTest {

    private fun cfg(): AppConfig = AppConfig(
        cameras = mutableListOf(
            Camera(name = "house-1", host = "10.0.0.1",
                credentials = Credentials("admin", "pw-one")),
            Camera(name = "house-2", host = "10.0.0.2",
                credentials = Credentials("admin", "pw-two"))),
        relay = RelayConfig(enabled = true, host = "relay.example",
            sessionToken = "tok-123"))

    @Test fun aThrowingProtectorRefusesInPlainWords() {
        try {
            Store.protectedCopy(cfg()) {
                throw java.security.ProviderException("Keystore operation failed")
            }
            fail("a dead keystore must refuse, not produce a config")
        } catch (e: Store.ProtectionRefused) {
            assertTrue(e.message!!.contains("Nothing was saved"))
            assertTrue(e.message!!.contains("can't protect saved logins"))
        }
    }

    @Test fun identityProtectorPassesValuesThrough() {
        val out = Store.protectedCopy(cfg()) { it }
        assertEquals("pw-one", out.cameras[0].credentials.password)
        assertEquals("pw-two", out.cameras[1].credentials.password)
        assertEquals("tok-123", out.relay.sessionToken)
    }

    @Test fun everyCredentialIsProtectedAndNothingElseChanges() {
        val src = cfg()
        val out = Store.protectedCopy(src) { "v1:$it" }
        assertEquals("v1:pw-one", out.cameras[0].credentials.password)
        assertEquals("v1:pw-two", out.cameras[1].credentials.password)
        assertEquals("v1:tok-123", out.relay.sessionToken)
        // usernames, names, hosts are not credentials — untouched
        assertEquals("admin", out.cameras[0].credentials.username)
        assertEquals("house-1", out.cameras[0].name)
        assertEquals("10.0.0.2", out.cameras[1].host)
        // the caller's config is never mutated (copy semantics)
        assertEquals("pw-one", src.cameras[0].credentials.password)
        assertEquals("tok-123", src.relay.sessionToken)
    }

    @Test fun emptyValuesNeverTouchTheProtector() {
        var calls = 0
        val out = Store.protectedCopy(AppConfig()) { calls++; it }
        assertEquals(0, calls)
        assertTrue(out.cameras.isEmpty())
    }
}
