package com.smartyourtv.housecameras.core

/** Self-healing restart policy (v2) — the standing self-repair law
 *  in code: restarts are CAPPED (a burst allowance with growing
 *  waits, then a slow lane that never gives up and never storms)
 *  and countable (restarts this hour feeds the diagnostics card).
 *  Pure logic, clock-injectable, unit-tested. */
class RestartPolicy(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private var burstUsed = 0
    private val restartTimes = java.util.ArrayDeque<Long>()
    @Volatile var inSlowLane = false; private set

    @Synchronized fun onFrame() { burstUsed = 0; inSlowLane = false }

    /** Delay before the next restart attempt. Always finite: the
     *  slow lane is 60 s forever — a tile degrades, it never dies. */
    @Synchronized fun nextDelayMs(): Long {
        restartTimes.addLast(clock())
        val cut = clock() - 3_600_000
        while (restartTimes.isNotEmpty() && restartTimes.first() < cut) restartTimes.removeFirst()
        return if (burstUsed < BURST_DELAYS.size) {
            val d = BURST_DELAYS[burstUsed]; burstUsed++; d
        } else { inSlowLane = true; SLOW_LANE_MS }
    }

    @Synchronized fun restartsThisHour(): Int {
        val cut = clock() - 3_600_000
        while (restartTimes.isNotEmpty() && restartTimes.first() < cut) restartTimes.removeFirst()
        return restartTimes.size
    }

    companion object {
        val BURST_DELAYS = longArrayOf(2_000, 4_000, 8_000, 16_000, 30_000, 30_000)
        const val SLOW_LANE_MS = 60_000L
    }
}

/** Global circuit breaker: when several tiles stall together, the
 *  problem is the ROAD, not the cameras. All supervisors share one
 *  extra backoff and the grid shows one plain banner instead of six
 *  tiles storming a wedged hop (2026-11-01 grid-wide collapse). */
object Breaker {
    private val stallAt = mutableMapOf<String, Long>()
    @Synchronized fun reportStall(camName: String, now: Long) { stallAt[camName] = now }
    @Synchronized fun reportHealthy(camName: String) { stallAt.remove(camName) }
    @Synchronized fun tripped(now: Long): Boolean =
        stallAt.values.count { now - it <= 30_000 } >= 3
    @Synchronized fun extraDelayMs(now: Long): Long = if (tripped(now)) 30_000 else 0
    @Synchronized fun clear() { stallAt.clear() }
}
