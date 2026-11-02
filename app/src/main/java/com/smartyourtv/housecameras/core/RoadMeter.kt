package com.smartyourtv.housecameras.core

/** Per-camera road/stream evidence (v2). Keeps what the 2026-11-01
 *  two-minute hold test had to measure by hand: frame freshness,
 *  stalls, reconnects — rolled into one plain word. Clock-injectable
 *  for unit tests. Window: 10 minutes. */
class RoadMeter(private val clock: () -> Long = { System.currentTimeMillis() }) {
    @Volatile var lastFrameAt: Long = 0L; private set
    @Volatile var lastConnectMs: Long = -1L
    private val stallStarts = java.util.ArrayDeque<Long>()
    private val stallDurations = java.util.ArrayDeque<Pair<Long, Long>>()  // (end, durationMs)
    private val reconnectTimes = java.util.ArrayDeque<Long>()
    private var stallOpenAt: Long = 0L

    private fun prune(now: Long) {
        val cut = now - WINDOW_MS
        while (stallStarts.isNotEmpty() && stallStarts.first() < cut) stallStarts.removeFirst()
        while (stallDurations.isNotEmpty() && stallDurations.first().first < cut) stallDurations.removeFirst()
        while (reconnectTimes.isNotEmpty() && reconnectTimes.first() < cut) reconnectTimes.removeFirst()
    }

    @Synchronized fun frame() { lastFrameAt = clock(); if (stallOpenAt > 0) endStall() }
    @Synchronized fun connected(ms: Long) { lastConnectMs = ms }
    @Synchronized fun stallBegin() { if (stallOpenAt == 0L) { stallOpenAt = clock(); stallStarts.addLast(stallOpenAt) } }
    @Synchronized fun endStall() {
        if (stallOpenAt > 0) { val now = clock(); stallDurations.addLast(now to (now - stallOpenAt)); stallOpenAt = 0L }
    }
    @Synchronized fun reconnect() { reconnectTimes.addLast(clock()) }

    @Synchronized fun frameAgeMs(): Long = if (lastFrameAt == 0L) -1 else clock() - lastFrameAt
    @Synchronized fun stallCount(): Int { prune(clock()); return stallStarts.size }
    @Synchronized fun reconnectCount(): Int { prune(clock()); return reconnectTimes.size }
    @Synchronized fun stallTotalMs(): Long { prune(clock()); return stallDurations.sumOf { it.second } }

    @Synchronized fun quality(): String {
        val now = clock(); prune(now)
        val wedged = stallStarts.size >= 3 || stallDurations.sumOf { it.second } > 60_000 ||
            (lastFrameAt > 0 && now - lastFrameAt > 30_000 && stallOpenAt > 0)
        if (wedged) return "Bad"
        if (stallStarts.isNotEmpty() || reconnectTimes.size >= 3) return "Shaky"
        return "Good"
    }

    companion object {
        const val WINDOW_MS = 600_000L
        /** Worst word wins across a set of meters. */
        fun summary(meters: Collection<RoadMeter>): String = when {
            meters.any { it.quality() == "Bad" } -> "Bad"
            meters.any { it.quality() == "Shaky" } -> "Shaky"
            else -> "Good"
        }
    }
}
