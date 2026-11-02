package com.smartyourtv.housecameras.core

import java.util.concurrent.ConcurrentHashMap

/** The live picture of every tile, kept by the supervisors and the
 *  resolver, read by the diagnostics card and the road banner.
 *  Freshness-based by construction: it carries frame times, not
 *  guesses. */
object LiveState {
    data class CamState(
        @Volatile var stateWord: String = "starting",
        @Volatile var sourceLabel: String = "Direct",   // Direct | Relay | Photos
        @Volatile var host: String = "",
        @Volatile var identityAnchor: String = "",
        @Volatile var lastFrameAt: Long = 0L,
        @Volatile var restartsThisHour: Int = 0,
        @Volatile var roadWord: String = "Good"
    )
    val cams = ConcurrentHashMap<String, CamState>()
    val meters = ConcurrentHashMap<String, RoadMeter>()
    @Volatile var breakerMessage: String = ""

    fun state(name: String): CamState = cams.getOrPut(name) { CamState() }
    fun meter(name: String): RoadMeter = meters.getOrPut(name) { RoadMeter() }
    fun roadSummary(): String =
        if (meters.isEmpty()) "Good" else RoadMeter.summary(meters.values)
}
