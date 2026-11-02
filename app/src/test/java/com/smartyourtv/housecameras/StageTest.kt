package com.smartyourtv.housecameras

import com.smartyourtv.housecameras.core.Camera
import com.smartyourtv.housecameras.core.CameraSource
import com.smartyourtv.housecameras.core.DvrConfig
import com.smartyourtv.housecameras.core.RelayConfig
import com.smartyourtv.housecameras.core.StageSpec
import org.junit.Assert.*
import org.junit.Test

class StageTest {
    @Test fun stageViewsMatchBankedPattern() {
        assertEquals(3, StageSpec.HOUSE.cols); assertEquals(2, StageSpec.HOUSE.rows)
        assertEquals(6, StageSpec.tiles(StageSpec.HOUSE))
        assertEquals(4, StageSpec.DVR.cols); assertEquals(1, StageSpec.DVR.rows)
        assertEquals(4, StageSpec.tiles(StageSpec.DVR))
        assertEquals(5, StageSpec.ALL.cols); assertEquals(2, StageSpec.ALL.rows)
        assertEquals(10, StageSpec.tiles(StageSpec.ALL))
        assertEquals(StageSpec.HOUSE, StageSpec.byName("junk"))  // safe default
        assertEquals(StageSpec.DVR, StageSpec.byName("DVR"))
    }

    @Test fun dvrPathsAndUrl() {
        val dvr = DvrConfig()
        assertEquals("/dvr-1", dvr.path(0))
        assertEquals("/dvr-4", dvr.path(3))
        val src = CameraSource(kind = "rtsp", path = dvr.path(1), port = dvr.port, via = "relay")
        val cam = Camera(name = "dvr-2", host = dvr.host, family = "generic", main = src, sub = src)
        assertEquals("rtsp://192.168.40.189:8554/dvr-2", cam.streamUrl(src, RelayConfig()))
    }
}
