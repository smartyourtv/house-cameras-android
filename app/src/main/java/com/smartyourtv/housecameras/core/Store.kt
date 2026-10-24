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

    fun seed(): AppConfig {
        val cams = mutableListOf<Camera>()
        val fleet = listOf(
            Triple("house-1", "192.168.40.25", "side yard / gate"),
            Triple("house-2", "192.168.40.82", "shed"),
            Triple("house-3", "192.168.40.131", "back patio"),
            Triple("house-4", "192.168.40.218", "garage / street (HEVC main)"),
            Triple("house-5", "192.168.40.233", "above garage / over the van"),
            Triple("house-6", "192.168.40.240", "flowering bushes")
        )
        for ((name, ip, note) in fleet) {
            val tpl = Templates.forFamily("hip2p")
            cams.add(Camera(name = name, host = ip, family = "hip2p",
                main = tpl.main.copy(), sub = tpl.sub.copy(), notes = note))
        }
        val tpl7 = Templates.forFamily("reolink")
        cams.add(Camera(name = "house-7", host = "192.168.40.150", family = "reolink", enabled = false,
            main = tpl7.main.copy(), sub = tpl7.sub.copy(),
            notes = "Front door — Reolink (registers as 'Front'; at .150 in the 2026-10-24 census — address may move until DHCP reservations land). Disabled until its password is entered here."))
        return AppConfig(cameras = cams)
    }

    fun load(ctx: Context): AppConfig {
        val f = file(ctx)
        if (!f.exists()) { val s = seed(); save(ctx, s); return s }
        return try {
            parse(JSONObject(f.readText()))
        } catch (e: Exception) {
            repairLog.add("Config was corrupt (${e.javaClass.simpleName}); backed up and restored seed defaults.")
            try { f.copyTo(File(ctx.filesDir, "config.json.bad"), overwrite = true) } catch (_: Exception) {}
            val s = seed(); save(ctx, s)
            val verify = try { parse(JSONObject(file(ctx).readText())); true } catch (_: Exception) { false }
            repairLog.add(if (verify) "Self-repair verified: restored config re-read OK."
                          else "Self-repair FAILED verification.")
            s
        }
    }

    fun save(ctx: Context, cfg: AppConfig) {
        val tmp = File(ctx.filesDir, "config.json.tmp")
        tmp.writeText(toJson(cfg).toString())
        tmp.renameTo(file(ctx))
    }

    fun toJson(cfg: AppConfig): JSONObject {
        val arr = JSONArray()
        for (c in cfg.cameras) {
            arr.put(JSONObject()
                .put("name", c.name).put("host", c.host).put("family", c.family)
                .put("enabled", c.enabled).put("notes", c.notes)
                .put("credentials", JSONObject()
                    .put("username", c.credentials.username)
                    .put("password", Crypto.protect(c.credentials.password)))
                .put("main", sourceJson(c.main)).put("sub", sourceJson(c.sub)))
        }
        return JSONObject().put("cameras", arr)
            .put("relay", JSONObject()
                .put("enabled", cfg.relay.enabled).put("host", cfg.relay.host)
                .put("port", cfg.relay.port).put("httpPort", cfg.relay.httpPort)
                .put("sessionToken", Crypto.protect(cfg.relay.sessionToken)))
            .put("lastNotifiedVersionCode", cfg.lastNotifiedVersionCode)
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
                main = sourceParse(o.optJSONObject("main")), sub = sourceParse(o.optJSONObject("sub"))))
        }
        val r = root.optJSONObject("relay") ?: JSONObject()
        cfg.relay = RelayConfig(r.optBoolean("enabled", false), r.optString("host"),
            r.optInt("port", 8554), r.optInt("httpPort", 8080),
            Crypto.unprotect(r.optString("sessionToken")))
        cfg.lastNotifiedVersionCode = root.optInt("lastNotifiedVersionCode", 0)
        return cfg
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
