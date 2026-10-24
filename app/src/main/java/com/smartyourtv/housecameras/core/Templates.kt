package com.smartyourtv.housecameras.core

/** Per-family templates (ingest ladder layer b) — identical table to the
 *  Windows sibling. Adding a family = adding one entry; the ladder,
 *  engine and UI never change (spec §1.4). */
data class Template(
    val label: String, val ports: List<Int>,
    val main: CameraSource, val sub: CameraSource,
    val snapshot: String, val match: List<String>
)

object Templates {
    private fun rtsp(path: String, port: Int = 554) =
        CameraSource(kind = "rtsp", path = path, port = port, via = "template")

    val ALL: Map<String, Template> = linkedMapOf(
        "hip2p" to Template("HiP2P / CamHi family", listOf(554, 80),
            rtsp("/11"), rtsp("/12"), "/snapshot.jpg", listOf("hichip", "hip2p", "camhi")),
        "reolink" to Template("Reolink", listOf(554, 80, 9000),
            rtsp("/h264Preview_01_main"), rtsp("/h264Preview_01_sub"),
            "/cgi-bin/api.cgi?cmd=Snap&channel=0", listOf("reolink")),
        "dahua" to Template("Dahua / Amcrest", listOf(554, 80),
            rtsp("/cam/realmonitor?channel=1&subtype=0"), rtsp("/cam/realmonitor?channel=1&subtype=1"),
            "/cgi-bin/snapshot.cgi", listOf("dahua", "amcrest")),
        "hikvision" to Template("Hikvision", listOf(554, 80, 8000),
            rtsp("/Streaming/Channels/101"), rtsp("/Streaming/Channels/102"),
            "/ISAPI/Streaming/channels/101/picture", listOf("hikvision")),
        "foscam" to Template("Foscam", listOf(88, 554),
            rtsp("/videoMain"), rtsp("/videoSub"),
            "/cgi-bin/CGIProxy.fcgi?cmd=snapPicture2", listOf("foscam")),
        "xmeye" to Template("XMEye family", listOf(554, 80, 34567),
            rtsp("/user=admin_password=tlJwpbo6_channel=1_stream=0.sdp"),
            rtsp("/user=admin_password=tlJwpbo6_channel=1_stream=1.sdp"),
            "/snapshot.jpg", listOf("xmeye", "xm ")),
        "tapo" to Template("TP-Link Tapo (RTSP must be enabled in the Tapo app)", listOf(554),
            rtsp("/stream1"), rtsp("/stream2"), "", listOf("tapo")),
        "generic" to Template("Generic / unknown", listOf(554, 80),
            rtsp(""), rtsp(""), "", emptyList())
    )

    fun forFamily(family: String?): Template = ALL[family ?: "generic"] ?: ALL.getValue("generic")

    fun identifyFamily(bannerText: String?): String? {
        val text = (bannerText ?: "").lowercase()
        for ((key, tpl) in ALL) {
            if (key == "generic") continue
            if (tpl.match.any { it in text }) return key
        }
        return null
    }
}

/** Generic RTSP known-path library (ladder layer c) + snapshot/MJPEG paths (layer d). */
object PathLibrary {
    val RTSP_PATHS = listOf(
        "/11", "/12", "/onvif1", "/onvif2",
        "/stream1", "/stream2", "/stream_0", "/stream_1",
        "/live/ch00_0", "/live/ch00_1", "/ch0_0.264", "/ch0_1.264",
        "/Streaming/Channels/101", "/Streaming/Channels/102",
        "/cam/realmonitor?channel=1&subtype=0", "/cam/realmonitor?channel=1&subtype=1",
        "/h264/ch1/main/av_stream", "/h264/ch1/sub/av_stream",
        "/video1", "/video2", "/live", "/rtsp", "/1", "/0"
    )
    val SNAPSHOT_PATHS = listOf(
        "/snapshot.jpg", "/snapshot.cgi", "/snap.jpg", "/image.jpg", "/jpg/image.jpg",
        "/cgi-bin/snapshot.cgi", "/webcapture.jpg?command=snap&channel=1",
        "/ISAPI/Streaming/channels/101/picture", "/cgi-bin/api.cgi?cmd=Snap&channel=0"
    )
    val MJPEG_PATHS = listOf(
        "/video.mjpg", "/mjpg/video.mjpg", "/videostream.cgi", "/cgi-bin/mjpeg", "/axis-cgi/mjpg/video.cgi"
    )
}
