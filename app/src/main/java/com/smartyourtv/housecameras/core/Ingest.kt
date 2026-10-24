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
