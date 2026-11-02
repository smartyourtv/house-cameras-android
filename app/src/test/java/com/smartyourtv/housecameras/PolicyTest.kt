package com.smartyourtv.housecameras

import com.smartyourtv.housecameras.core.Breaker
import com.smartyourtv.housecameras.core.RestartPolicy
import com.smartyourtv.housecameras.core.RoadMeter
import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun restartDelaysBurstThenSlowLane() {
        var now = 0L
        val p = RestartPolicy { now }
        val seq = (1..8).map { p.nextDelayMs() }
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 60000L, 60000L), seq)
        assertTrue(p.inSlowLane)
        p.onFrame()
        assertFalse(p.inSlowLane)
        assertEquals(2000L, p.nextDelayMs())
    }

    @Test fun restartsThisHourCountsAndExpires() {
        var now = 0L
        val p = RestartPolicy { now }
        p.nextDelayMs(); p.nextDelayMs()
        assertEquals(2, p.restartsThisHour())
        now += 3_700_000
        assertEquals(0, p.restartsThisHour())
    }

    @Test fun breakerTripsOnThreeTilesNotTwo() {
        Breaker.clear()
        val now = 100_000L
        Breaker.reportStall("house-1", now); Breaker.reportStall("house-2", now)
        assertFalse(Breaker.tripped(now))
        assertEquals(0L, Breaker.extraDelayMs(now))
        Breaker.reportStall("house-4", now)
        assertTrue(Breaker.tripped(now))
        assertEquals(30_000L, Breaker.extraDelayMs(now))
        assertFalse(Breaker.tripped(now + 31_000))   // window passes
        Breaker.reportHealthy("house-4")
        assertFalse(Breaker.tripped(now))
        Breaker.clear()
    }

    @Test fun roadMeterQualityWords() {
        var now = 1_000_000L   // nonzero: RoadMeter uses 0 as its "no frame yet" sentinel
        val m = RoadMeter { now }
        assertEquals("Good", m.quality())
        m.frame(); assertEquals(0L, m.frameAgeMs())
        m.reconnect(); m.reconnect(); m.reconnect()
        assertEquals("Shaky", m.quality())
        val m2 = RoadMeter { now }
        repeat(3) { m2.stallBegin(); now += 1000; m2.frame() }
        assertEquals("Bad", m2.quality())
        assertEquals(3, m2.stallCount())
    }

    @Test fun roadSummaryWorstWins() {
        var now = 0L
        val good = RoadMeter { now }
        val shaky = RoadMeter { now }
        shaky.reconnect(); shaky.reconnect(); shaky.reconnect()
        assertEquals("Shaky", RoadMeter.summary(listOf(good, shaky)))
        assertEquals("Good", RoadMeter.summary(listOf(good)))
    }
}
