package com.smartyourtv.housecameras.core

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.*
import java.util.Collections
import java.util.UUID
import java.util.concurrent.*

/** All four discovery methods (spec §1.1), results merged.
 *  Unknown hosts are reported, never interrogated beyond banners (§3). */
data class FoundHost(
    val host: String, var family: String? = null, var via: String = "",
    var detail: String = ""
)

object Discovery {

    /** ONVIF WS-Discovery: SOAP Probe to 239.255.255.250:3702. */
    fun onvif(timeoutMs: Int = 2500): List<FoundHost> {
        val results = mutableListOf<FoundHost>()
        try {
            val id = "urn:uuid:" + UUID.randomUUID()
            val probe = """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><s:Header><a:MessageID>$id</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action></s:Header><s:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></s:Body></s:Envelope>"""
            val sock = MulticastSocket(null as SocketAddress?).apply {
                reuseAddress = true; soTimeout = 400; bind(InetSocketAddress(3702))
            }
            val addr = InetAddress.getByName("239.255.255.250")
            val bytes = probe.toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(bytes, bytes.size, addr, 3702))
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(8192)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length, Charsets.UTF_8)
                    val host = pkt.address.hostAddress ?: continue
                    if (results.none { it.host == host })
                        results.add(FoundHost(host, Templates.identifyFamily(text) ?: "generic",
                            "onvif", "ONVIF device"))
                } catch (_: SocketTimeoutException) { }
            }
            sock.close()
        } catch (_: Exception) { }
        return results
    }

    /** HiP2P-family VSCP LAN search: UDP broadcast of the probe string
     *  recovered from the vendor SearchLib.dll. Best-effort — the fleet's
     *  reliable path is the sweep + RTSP template. */
    fun hip2pVscp(timeoutMs: Int = 2000): List<FoundHost> {
        val results = mutableListOf<FoundHost>()
        try {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true; broadcast = true; soTimeout = 300
                bind(InetSocketAddress(0))
            }
            val msg = "SEARCH * HDS/1.0".toByteArray(Charsets.UTF_8)
            for (port in listOf(8000, 8899, 8600, 3702)) {
                try { sock.send(DatagramPacket(msg, msg.size,
                    InetAddress.getByName("255.255.255.255"), port)) } catch (_: Exception) {}
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size); sock.receive(pkt)
                    val host = pkt.address.hostAddress ?: continue
                    if (results.none { it.host == host })
                        results.add(FoundHost(host, "hip2p", "vscp", "HiP2P-family reply"))
                } catch (_: SocketTimeoutException) { }
            }
            sock.close()
        } catch (_: Exception) { }
        return results
    }

    /** Reolink identification for one host: TCP 9000 or the HTTP API. */
    fun reolinkCheck(host: String): Boolean {
        try {
            Socket().use { it.connect(InetSocketAddress(host, 9000), 600); return true }
        } catch (_: Exception) { }
        return try {
            val conn = URL("http://$host/cgi-bin/api.cgi?cmd=GetDevInfo")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 800; conn.readTimeout = 800
            val body = BufferedReader(InputStreamReader(conn.inputStream)).readText()
            body.contains("Reolink", ignoreCase = true)
        } catch (_: Exception) { false }
    }

    /** Plain LAN sweep of a /24: connect scan + banner fingerprint. */
    fun lanSweep(subnetPrefix: String, ports: List<Int> = listOf(554, 80, 8000, 8899, 9000),
                 onFound: (FoundHost) -> Unit = {}): List<FoundHost> {
        val results = Collections.synchronizedList(mutableListOf<FoundHost>())
        val pool = Executors.newFixedThreadPool(48)
        val futures = mutableListOf<Future<*>>()
        for (i in 1..254) {
            val host = "$subnetPrefix.$i"
            futures.add(pool.submit {
                var open = false; var banner = ""
                for (p in ports) {
                    try { Socket().use { it.connect(InetSocketAddress(host, p), 450); open = true } } catch (_: Exception) {}
                    if (open && p == 554) banner += rtspBanner(host)
                    if (open && p == 80) banner += httpBanner(host)
                    if (open) break
                }
                if (open) {
                    var fam = Templates.identifyFamily(banner)
                    if (fam == null && reolinkCheck(host)) fam = "reolink"
                    val fh = FoundHost(host, fam ?: "generic", "sweep",
                        banner.trim().take(80).ifEmpty { "open camera port" })
                    results.add(fh); onFound(fh)
                }
            })
        }
        futures.forEach { try { it.get(30, TimeUnit.SECONDS) } catch (_: Exception) {} }
        pool.shutdown()
        return results
    }

    fun rtspBanner(host: String, port: Int = 554): String = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 600); s.soTimeout = 700
            s.getOutputStream().write("OPTIONS rtsp://$host:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n".toByteArray())
            val buf = ByteArray(512); val n = s.getInputStream().read(buf)
            if (n > 0) String(buf, 0, n) else ""
        }
    } catch (_: Exception) { "" }

    fun httpBanner(host: String): String = try {
        val conn = URL("http://$host/").openConnection() as HttpURLConnection
        conn.connectTimeout = 700; conn.readTimeout = 700
        conn.setRequestProperty("User-Agent", "HouseCameras-Discovery")
        val server = conn.getHeaderField("Server") ?: ""
        val realm = conn.getHeaderField("WWW-Authenticate") ?: ""
        "$server $realm"
    } catch (_: Exception) { "" }

    fun merge(vararg lists: List<FoundHost>): List<FoundHost> {
        val byHost = linkedMapOf<String, FoundHost>()
        for (list in lists) for (fh in list) {
            val prev = byHost[fh.host]
            if (prev == null) byHost[fh.host] = fh
            else {
                if (prev.family == "generic" && fh.family != "generic") prev.family = fh.family
                prev.via += "+" + fh.via
            }
        }
        return byHost.values.toList()
    }

    fun localSubnetPrefix(ctx: android.content.Context): String {
        try {
            val wifi = ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val ip = wifi.connectionInfo.ipAddress
            if (ip != 0) return "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}"
        } catch (_: Exception) { }
        return "192.168.40"
    }
}
