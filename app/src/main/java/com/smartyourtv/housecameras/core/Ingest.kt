package com.smartyourtv.housecameras.core

import android.graphics.BitmapFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Base64

/** The ordered ingest ladder (spec §1.2). A layer wins only with a
 *  verified picture: for RTSP that is a successful DESCRIBE (the player
 *  then confirms actual decode and advances the ladder on error);
 *  for snapshots, a JPEG that actually parses. Never a port merely open. */
object Ingest {

    fun rtspDescribe(cam: Camera, source: CameraSource): Boolean {
        if (cam.host.isEmpty()) return false
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(cam.host, source.port), 900); s.soTimeout = 1200
                val path = source.path.ifEmpty { "/" }
                val req = StringBuilder("DESCRIBE rtsp://${cam.host}:${source.port}$path RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n")
                if (cam.credentials.hasLogin()) {
                    val basic = Base64.getEncoder().encodeToString(
                        "${cam.credentials.username}:${cam.credentials.password}".toByteArray())
                    req.append("Authorization: Basic $basic\r\n")
                }
                req.append("\r\n")
                s.getOutputStream().write(req.toString().toByteArray())
                val line = BufferedReader(InputStreamReader(s.getInputStream())).readLine() ?: ""
                // 200 = stream exists; 401 = exists but needs the digest step the player performs.
                line.contains(" 200") || line.contains(" 401")
            }
        } catch (_: Exception) { false }
    }

    fun fetchSnapshot(cam: Camera, path: String): Boolean {
        if (cam.host.isEmpty() || path.isEmpty()) return false
        return try {
            val conn = URL("http://${cam.host}$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 1200; conn.readTimeout = 1500
            if (cam.credentials.hasLogin()) {
                val basic = java.util.Base64.getEncoder().encodeToString(
                    "${cam.credentials.username}:${cam.credentials.password}".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $basic")
            }
            val bytes = conn.inputStream.readBytes()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null
        } catch (_: Exception) { false }
    }

    fun httpReachable(cam: Camera, path: String): Boolean {
        if (cam.host.isEmpty() || path.isEmpty()) return false
        return try {
            val conn = URL("http://${cam.host}$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000; conn.readTimeout = 1200
            conn.responseCode in 200..499
        } catch (_: Exception) { false }
    }

    /** Minimal ONVIF GetStreamUri over the device service (layer a).
     *  Returns the stream path+port parsed from the returned URI, or null.
     *  Kept deliberately small: most fleet cameras answer the template
     *  layer; ONVIF is tried first where a device service is known. */
    fun onvifStreamPath(cam: Camera): CameraSource? {
        if (cam.host.isEmpty()) return null
        return try {
            val body = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema"><s:Body><trt:GetStreamUri><trt:StreamSetup><tt:Stream>RTP-Unicast</tt:Stream><tt:Transport><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>MainStream</trt:ProfileToken></trt:GetStreamUri></s:Body></s:Envelope>"""
            val conn = URL("http://${cam.host}/onvif/device_service").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 1200; conn.readTimeout = 1500
            conn.setRequestProperty("Content-Type", "application/soap+xml")
            conn.outputStream.write(body.toByteArray())
            val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            val m = Regex("""rtsp://[^/<]+(:\d+)?(/[^<\s]*)""").find(text) ?: return null
            val port = m.groupValues[1].removePrefix(":").toIntOrNull() ?: 554
            CameraSource(kind = "rtsp", path = m.groupValues[2], port = port, via = "onvif")
        } catch (_: Exception) { null }
    }

    /** ONVIF GetDeviceInformation serial read (Leg 1): the serial is
     *  this fleet's LAN identity anchor (on the house cameras it
     *  equals the MAC without separators, ONVIF-proven 2026-11-20).
     *  The device service answers on 8080 on this fleet; port 80 is
     *  the fallback. Tries no-auth first, then a WS-UsernameToken
     *  digest with the camera's login. Returns "" when the device
     *  does not say — a failed read loses an anchor for this pass;
     *  it never invents one. */
    fun onvifDeviceSerial(cam: Camera): String {
        if (cam.host.isEmpty()) return ""
        for (port in listOf(8080, 80)) {
            deviceSerialAt(cam, port, "")?.let { return it }
            if (cam.credentials.hasLogin())
                deviceSerialAt(cam, port, usernameToken(cam))?.let { return it }
        }
        return ""
    }

    /** Ask the camera what it can do (future-proofing amendment,
     *  rule 1): the ONVIF services it LISTS, its profiles' codecs,
     *  and PTZ kept as "advertised" until a read-only GetStatus
     *  answers — today's fleet advertises PTZ it does not have
     *  fitted. Returns null when the device does not answer ONVIF
     *  at all. Mirrors the Python core's learn_capabilities. */
    fun onvifCapabilities(cam: Camera): Capabilities? {
        if (cam.host.isEmpty()) return null
        for (port in listOf(8080, 80)) {
            capabilitiesAt(cam, port, "")?.let { return it }
            if (cam.credentials.hasLogin())
                capabilitiesAt(cam, port, usernameToken(cam))?.let { return it }
        }
        return null
    }

    private fun soapPost(url: String, body: String): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"; conn.doOutput = true
        conn.connectTimeout = 1200; conn.readTimeout = 1500
        conn.setRequestProperty("Content-Type", "application/soap+xml")
        conn.outputStream.write(body.toByteArray())
        BufferedReader(InputStreamReader(conn.inputStream)).readText()
    } catch (_: Exception) { null }

    private fun capabilitiesAt(cam: Camera, port: Int, header: String): Capabilities? {
        val dev = "http://${cam.host}:$port/onvif/device_service"
        val svcBody = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">$header<s:Body><tds:GetServices><tds:IncludeCapability>false</tds:IncludeCapability></tds:GetServices></s:Body></s:Envelope>"""
        val svcText = soapPost(dev, svcBody) ?: return null
        val services = linkedMapOf<String, String>()
        for (m in Regex("""<[^>]*Namespace>([^<]+)</[^>]*Namespace>\s*<[^>]*XAddr>([^<]+)</""")
            .findAll(svcText)) {
            val parts = m.groupValues[1].trimEnd('/').split("/")
            val short = if (parts.last() == "wsdl" && parts.size >= 2)
                parts[parts.size - 2] else m.groupValues[1]
            services[short] = m.groupValues[2]
        }
        if (services.isEmpty()) return null
        val caps = Capabilities(
            services = services.keys.joinToString(","),
            events = if ("events" in services) "advertised" else "absent",
            ptz = if ("ptz" in services) "advertised" else "absent",
            learnedAt = System.currentTimeMillis())
        // Profiles (via the media service) give the main codec —
        // and the main token the PTZ proof needs.
        var mainToken = ""
        val capBody = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">$header<s:Body><tds:GetCapabilities><tds:Category>Media</tds:Category></tds:GetCapabilities></s:Body></s:Envelope>"""
        val mediaX = soapPost(dev, capBody)?.let {
            Regex("""<[^>]*XAddr>([^<]+)</""").find(it)?.groupValues?.get(1) }
        if (mediaX != null) {
            val profBody = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl">$header<s:Body><trt:GetProfiles/></s:Body></s:Envelope>"""
            val profText = soapPost(mediaX, profBody)
            if (profText != null) {
                var best = 0L
                for (p in Regex("""<[^>]*Profiles[^>]*token="([^"]+)"[^>]*>(.*?)</[^>]*Profiles>""",
                        RegexOption.DOT_MATCHES_ALL).findAll(profText)) {
                    val w = Regex("""<[^>]*Width>(\d+)<""").find(p.groupValues[2])
                        ?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val h = Regex("""<[^>]*Height>(\d+)<""").find(p.groupValues[2])
                        ?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val enc = Regex("""<[^>]*Encoding>([^<]+)<""").find(p.groupValues[2])
                        ?.groupValues?.get(1) ?: ""
                    // D8 seat: a profile carrying an audio channel
                    // is what two-way talk would start from.
                    if (p.groupValues[2].contains("Audio")) caps.talk = "advertised"
                    if (w * h >= best) { best = w * h; mainToken = p.groupValues[1]
                        caps.mainCodec = "$enc ${w}x$h" }
                }
            }
        }
        // The PTZ proof: GetStatus must answer with a real status.
        val ptzX = services["ptz"]
        if (ptzX != null && mainToken.isNotEmpty()) {
            val stBody = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">$header<s:Body><tptz:GetStatus><tptz:ProfileToken>$mainToken</tptz:ProfileToken></tptz:GetStatus></s:Body></s:Envelope>"""
            val stText = soapPost(ptzX, stBody)
            if (stText != null && stText.contains("PTZStatus")) {
                caps.ptz = "proven"
                // D2 seat: the preset list rides the record, so
                // the control area renders from data.
                val prBody = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tptz="http://www.onvif.org/ver20/ptz/wsdl">$header<s:Body><tptz:GetPresets><tptz:ProfileToken>$mainToken</tptz:ProfileToken></tptz:GetPresets></s:Body></s:Envelope>"""
                val prText = soapPost(ptzX, prBody)
                if (prText != null) {
                    caps.ptzPresets = Regex("""<[^>]*Preset[^>]*>.*?<[^>]*Name>([^<]+)</""",
                        RegexOption.DOT_MATCHES_ALL).findAll(prText)
                        .map { it.groupValues[1] }.joinToString(",")
                }
            }
        }
        if (caps.talk.isEmpty()) caps.talk = "absent"
        return caps
    }

    private fun deviceSerialAt(cam: Camera, port: Int, header: String): String? {        return try {
            val body = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">$header<s:Body><tds:GetDeviceInformation/></s:Body></s:Envelope>"""
            val conn = URL("http://${cam.host}:$port/onvif/device_service")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 1200; conn.readTimeout = 1500
            conn.setRequestProperty("Content-Type", "application/soap+xml")
            conn.outputStream.write(body.toByteArray())
            val text = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            Regex("""SerialNumber>([0-9A-Za-z]+)<""").find(text)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private fun usernameToken(cam: Camera): String {
        val nonce = java.util.UUID.randomUUID().toString().toByteArray()
        val created = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val digest = java.security.MessageDigest.getInstance("SHA-1").apply {
            update(nonce); update(created.toByteArray())
            update(cam.credentials.password.toByteArray())
        }.digest()
        val b64 = java.util.Base64.getEncoder()
        return """<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${cam.credentials.username}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">${b64.encodeToString(digest)}</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${b64.encodeToString(nonce)}</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-utility-1.0.xsd">$created</Created></UsernameToken></Security></s:Header>"""
    }

    /** Run the full ladder for a camera; returns the winning main source
     *  (and sets sub where the winning layer provides one), or null with
     *  the honest-limit meaning: this device offers no local stream. */
    fun resolve(cam: Camera): CameraSource? {
        // (a) ONVIF
        onvifStreamPath(cam)?.let { cam.main = it; return it }
        // (b) vendor template
        val tpl = Templates.forFamily(cam.family)
        if (tpl.main.path.isNotEmpty() && rtspDescribe(cam, tpl.main)) {
            cam.main = tpl.main.copy(); cam.sub = tpl.sub.copy(); return cam.main
        }
        // (c) generic RTSP known-path probing
        for (p in PathLibrary.RTSP_PATHS) {
            val src = CameraSource(kind = "rtsp", path = p, port = 554, via = "generic")
            if (rtspDescribe(cam, src)) {
                cam.main = src
                cam.sub = CameraSource(kind = "rtsp", path = p, port = 554, via = "generic")
                return src
            }
        }
        // (d) MJPEG, then JPEG snapshot polling — last resort
        for (p in PathLibrary.MJPEG_PATHS) {
            if (httpReachable(cam, p)) {
                val src = CameraSource(kind = "mjpeg", path = p, port = 80, scheme = "http", via = "mjpeg")
                cam.main = src; cam.sub = src; return src
            }
        }
        val snapPaths = listOf(tpl.snapshot) + PathLibrary.SNAPSHOT_PATHS
        for (p in snapPaths) {
            if (p.isNotEmpty() && fetchSnapshot(cam, p)) {
                val src = CameraSource(kind = "snapshot", path = p, port = 80, scheme = "http", via = "snapshot")
                cam.main = src; cam.sub = src; return src
            }
        }
        return null
    }
}
