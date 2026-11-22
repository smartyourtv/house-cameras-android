package com.smartyourtv.housecameras.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Config load/save in app-private storage, with self-repair:
 *  a corrupt config is backed up to config.json.bad, safe defaults
 *  (the seed fleet) are restored, the restore is verified by re-read,
 *  and every repair is logged. Credentials are Keystore-encrypted. */
object Store {
    val repairLog = mutableListOf<String>()

    private fun file(ctx: Context) = File(ctx.filesDir, "config.json")

    /** The starter list. PORTABILITY RULE (owner, 2026-11-22):
     *  no house's camera addresses or layout live in the app — a
     *  fresh install starts EMPTY and discovery is how cameras are
     *  added. A machine may carry an optional fleet-seed.json in
     *  its app storage, placed there at install time by whoever
     *  sets the machine up; when no config exists yet (or a repair
     *  restores), that seed becomes the starting list. Seeds are
     *  password-free by rule, enforced here (Pack D F18). */
    fun seed(ctx: Context): AppConfig {
        val sf = File(ctx.filesDir, "fleet-seed.json")
        if (sf.exists()) {
            try {
                val raw = JSONObject(sf.readText())
                val arr = raw.optJSONArray("cameras") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val pw = arr.optJSONObject(i)?.optJSONObject("credentials")
                        ?.optString("password", "") ?: ""
                    if (pw.isNotEmpty()) {
                        repairLog.add("The fleet seed carries a camera password — refused. Seeds are password-free by rule; the login is entered on the device.")
                        return AppConfig()
                    }
                }
                return parse(raw)
            } catch (e: Exception) {
                repairLog.add("The fleet seed could not be read (${e.javaClass.simpleName}) — starting with no cameras.")
                return AppConfig()
            }
        }
        return AppConfig()
    }

    fun load(ctx: Context): AppConfig {
        val f = file(ctx)
        if (!f.exists()) { val s = seed(ctx); save(ctx, s); return s }
        return try {
            parse(JSONObject(f.readText()))
        } catch (e: Exception) {
            repairLog.add("Config was corrupt (${e.javaClass.simpleName}); backed up and restored the starter list.")
            try { f.copyTo(File(ctx.filesDir, "config.json.bad"), overwrite = true) } catch (_: Exception) {}
            val s = seed(ctx); save(ctx, s)
            val verify = try { parse(JSONObject(file(ctx).readText())); true } catch (_: Exception) { false }
            repairLog.add(if (verify) "Self-repair verified: restored config re-read OK."
                          else "Self-repair FAILED verification.")
            persistRepairLog(ctx)
            s
        }
    }

    /** Pack E M4: repairs are written to the persistent event log
     *  at the time they happen — a repair from a previous process
     *  must still be visible on the Status screen after a restart,
     *  not live only in this process's memory. */
    private fun persistRepairLog(ctx: Context) {
        try {
            val dir = File(ctx.filesDir, "events").apply { mkdirs() }
            val f = File(dir, "repairs.log")
            for (line in repairLog) {
                if (!f.exists() || !f.readText().contains(line)) {
                    f.appendText("$line\n")
                }
            }
        } catch (_: Exception) {}
    }

    /** Repair lines across process restarts (Pack E M4), for the
     *  Status screen: this process's lines plus the persisted ones. */
    fun repairHistory(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        try {
            val f = File(ctx.filesDir, "events/repairs.log")
            if (f.exists()) out.addAll(f.readLines().filter { it.isNotBlank() })
        } catch (_: Exception) {}
        for (line in repairLog) if (line !in out) out.add(line)
        return out
    }

    /** 0b Finding 1 (2026-11-22): a save that cannot protect the
     *  credentials it carries must REFUSE — nothing written, the
     *  caller told in plain words, the form left standing. Before
     *  this, a device whose keystore refused the key crashed
     *  every save uncaught (Crypto.protect threw straight through
     *  toJson) and ate the form with it. */
    sealed class SaveResult {
        object Saved : SaveResult()
        data class Refused(val reason: String) : SaveResult()
    }
    class ProtectionRefused(message: String) : Exception(message)

    const val PROTECTION_REFUSAL_WORDS =
        "Nothing was saved. This device can't protect saved logins — " +
        "its secure storage refused to make or use the key. What you " +
        "typed is still on this screen; nothing was written or lost. " +
        "The same fault shows in the app's self-test — this device " +
        "may not be able to keep viewer logins at all."

    @Volatile var lastSaveRefusal: String = ""
    @Volatile var lastSaveRefusalAt: Long = 0L

    /** Every credential in the config, run through [protect], on
     *  a copy — the caller's config is never mutated. Empty values
     *  never touch the protector (a credential-free save needs no
     *  keystore at all). Any failure becomes a ProtectionRefused:
     *  a config is never half-protected. Pure apart from the
     *  lambda, so the unit tests drive it directly. */
    fun protectedCopy(cfg: AppConfig, protect: (String) -> String): AppConfig {
        fun p(s: String): String = if (s.isEmpty()) s else protect(s)
        try {
            val cams = cfg.cameras.map { c ->
                c.copy(credentials = Credentials(c.credentials.username, p(c.credentials.password)))
            }.toMutableList()
            return cfg.copy(cameras = cams,
                relay = cfg.relay.copy(sessionToken = p(cfg.relay.sessionToken)))
        } catch (e: Exception) {
            throw ProtectionRefused(PROTECTION_REFUSAL_WORDS)
        }
    }

    fun save(ctx: Context, cfg: AppConfig): SaveResult {
        val json = try { toJson(cfg) } catch (e: ProtectionRefused) {
            lastSaveRefusal = e.message ?: PROTECTION_REFUSAL_WORDS
            lastSaveRefusalAt = System.currentTimeMillis()
            repairLog.add("Settings NOT saved — this device can't protect saved logins (secure storage refused). Nothing was written.")
            persistRepairLog(ctx)
            return SaveResult.Refused(lastSaveRefusal)
        }
        val tmp = File(ctx.filesDir, "config.json.tmp")
        tmp.writeText(json.toString())
        // Pack E L7: a failed rename used to leave a stale config
        // with no error anywhere. Check it, and say so.
        if (!tmp.renameTo(file(ctx))) {
            repairLog.add("Config save FAILED — the new settings could not replace the old file.")
            persistRepairLog(ctx)
            try { tmp.copyTo(file(ctx), overwrite = true) } catch (_: Exception) {}
        }
        return SaveResult.Saved
    }

    fun toJson(cfg: AppConfig): JSONObject = toJsonRaw(protectedCopy(cfg) { Crypto.protect(it) })

    private fun toJsonRaw(cfg: AppConfig): JSONObject {
        val arr = JSONArray()
        for (c in cfg.cameras) {
            arr.put(JSONObject()
                .put("name", c.name).put("host", c.host).put("family", c.family)
                .put("enabled", c.enabled).put("notes", c.notes)
                .put("credentials", JSONObject()
                    .put("username", c.credentials.username)
                    .put("password", c.credentials.password))
                .put("main", sourceJson(c.main)).put("sub", sourceJson(c.sub))
                .put("identity", JSONObject()
                    .put("mac", c.identity.mac)
                    .put("aliases", JSONArray(c.identity.macAliases))
                    .put("uuid", c.identity.onvifUuid)
                    .put("serial", c.identity.onvifSerial)
                    .put("uid", c.identity.hip2pUid)
                    .put("fp", c.identity.fingerprint)
                    .put("verified", c.identity.lastVerifiedAt))
                .put("capabilities", JSONObject()
                    .put("services", c.capabilities.services)
                    .put("ptz", c.capabilities.ptz)
                    .put("events", c.capabilities.events)
                    .put("mainCodec", c.capabilities.mainCodec)
                    .put("learnedAt", c.capabilities.learnedAt)
                    .put("ptzPresets", c.capabilities.ptzPresets)
                    .put("talk", c.capabilities.talk)
                    .put("nativeEvents", c.capabilities.nativeEvents))
                .put("keepalive", c.keepalive)
                .put("eventSource", c.eventSource)
                .put("claimToken", c.claimToken)
                .put("zones", c.zones)
                .put("viewers", c.viewers)
                .put("recorder", c.recorder)
                .put("siteId", c.siteId)
                .put("setupClass", c.setupClass))
        }
        return JSONObject().put("cameras", arr)
            .put("steadyPicture", cfg.steadyPicture)
            .put("homeView", cfg.homeView)
            .put("bootToCameras", cfg.bootToCameras)
            .put("dvr", JSONObject().put("host", cfg.dvr.host)
                .put("port", cfg.dvr.port).put("channels", cfg.dvr.channels))
            .put("relay", JSONObject()
                .put("enabled", cfg.relay.enabled).put("host", cfg.relay.host)
                .put("port", cfg.relay.port).put("httpPort", cfg.relay.httpPort)
                .put("sessionToken", cfg.relay.sessionToken))
            .put("lastNotifiedVersionCode", cfg.lastNotifiedVersionCode)
            .put("updateNoticeDismissed", cfg.updateNoticeDismissed)
    }

    fun parse(root: JSONObject): AppConfig {
        val cfg = AppConfig()
        val arr = root.optJSONArray("cameras") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val cred = o.optJSONObject("credentials") ?: JSONObject()
            cfg.cameras.add(Camera(
                name = o.optString("name"), host = o.optString("host"),
                family = o.optString("family", "generic"), enabled = o.optBoolean("enabled", true),
                notes = o.optString("notes"),
                credentials = Credentials(cred.optString("username"),
                    Crypto.unprotect(cred.optString("password"))),
                main = sourceParse(o.optJSONObject("main")), sub = sourceParse(o.optJSONObject("sub")),
                identity = identityParse(o.optJSONObject("identity")),
                capabilities = capabilitiesParse(o.optJSONObject("capabilities")),
                keepalive = o.optString("keepalive"),
                eventSource = o.optString("eventSource"),
                claimToken = o.optString("claimToken"),
                zones = o.optString("zones"),
                viewers = o.optString("viewers", "household").ifEmpty { "household" },
                recorder = o.optString("recorder"),
                siteId = o.optString("siteId", "home").ifEmpty { "home" },
                setupClass = o.optString("setupClass")))
        }
        val r = root.optJSONObject("relay") ?: JSONObject()
        cfg.relay = RelayConfig(r.optBoolean("enabled", false),
            r.optString("host"),
            r.optInt("port", 8554), r.optInt("httpPort", 8888),
            Crypto.unprotect(r.optString("sessionToken")))
        cfg.lastNotifiedVersionCode = root.optInt("lastNotifiedVersionCode", 0)
        cfg.updateNoticeDismissed = root.optInt("updateNoticeDismissed", 0)
        cfg.steadyPicture = root.optBoolean("steadyPicture", true)
        cfg.homeView = root.optString("homeView", "HOUSE")
        cfg.bootToCameras = root.optBoolean("bootToCameras", true)
        val dv = root.optJSONObject("dvr")
        if (dv != null) cfg.dvr = DvrConfig(
            dv.optString("host"),
            dv.optInt("port", 8554), dv.optInt("channels", 4))
        return cfg
    }

    private fun capabilitiesParse(o: JSONObject?): Capabilities {
        if (o == null) return Capabilities()
        return Capabilities(o.optString("services"), o.optString("ptz"),
            o.optString("events"), o.optString("mainCodec"),
            o.optLong("learnedAt", 0), o.optString("ptzPresets"),
            o.optString("talk"), o.optBoolean("nativeEvents", false))
    }

    private fun identityParse(o: JSONObject?): Identity {
        if (o == null) return Identity()
        val aliases = mutableListOf<String>()
        val arr = o.optJSONArray("aliases")
        if (arr != null) for (i in 0 until arr.length()) aliases.add(arr.optString(i))
        return Identity(o.optString("mac"), aliases, o.optString("uuid"),
            o.optString("serial"), o.optString("uid"), o.optString("fp"),
            o.optLong("verified", 0))
    }

    private fun sourceJson(s: CameraSource) = JSONObject()
        .put("kind", s.kind).put("path", s.path).put("port", s.port)
        .put("scheme", s.scheme).put("via", s.via)

    private fun sourceParse(o: JSONObject?): CameraSource {
        if (o == null) return CameraSource()
        return CameraSource(o.optString("kind"), o.optString("path"),
            o.optInt("port", 554), o.optString("scheme", "rtsp"), o.optString("via"))
    }
}
