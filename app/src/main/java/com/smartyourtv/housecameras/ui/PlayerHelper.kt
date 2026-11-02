package com.smartyourtv.housecameras.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.smartyourtv.housecameras.core.Camera
import com.smartyourtv.housecameras.core.CameraSource
import com.smartyourtv.housecameras.core.RelayConfig
import com.smartyourtv.housecameras.core.HttpFetch
import com.smartyourtv.housecameras.core.SpsGuard
import com.smartyourtv.housecameras.core.Templates
import kotlin.concurrent.thread

/** Plays one camera source — in v2 this is the tile's SUPERVISOR:
 *  it owns the player, judges health by FRAME FRESHNESS (playback
 *  position advancing), restarts dead streams under RestartPolicy
 *  caps, feeds the RoadMeter + LiveState, and shares the global
 *  circuit breaker. RTSP goes to ExoPlayer with letterbox
 *  (RESIZE_MODE_FIT — largest picture, true shape, no stretch/crop).
 *  Snapshot sources poll JPEGs into an ImageView (FIT_CENTER = same
 *  letterbox rule).
 *
 *  Buffering follows the owner's "Steady picture" ruling: ON = deep
 *  buffer (grid ~10 s, single ~4 s) to ride out a shaky road; OFF =
 *  lowest latency. Read at stream start; changing the setting
 *  re-opens streams (MainActivity rebuilds on resume). */
class CameraPlayer(
    private val ctx: Context,
    private val cam: Camera,
    private val source: CameraSource,
    private val relay: RelayConfig,
    private val singleView: Boolean = false,
    private val steady: Boolean = true,
    private val onHostHealed: (() -> Unit)? = null,
    private val onStatus: (String, Long) -> Unit
) {
    var player: ExoPlayer? = null
    private var pollThread: Thread? = null
    @Volatile var stopped = false
    var lastGoodFrameAt: Long = 0

    // Relay session tolerance (relay spec): a relay path may report
    // not-ready for up to ~30 s after a session starts, and a stream
    // ends outright at the 2 h cap / session stop. Relay mode retries
    // every 4 s for ~90 s before declaring "not connected", then
    // keeps a quiet 60 s slow lane (a session may start later).
    // LAN mode runs on RestartPolicy (burst caps -> 60 s slow lane).
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var retryPending = false
    private var viewRef: PlayerView? = null

    private var fallbackImageRef: android.widget.ImageView? = null

    // --- v2 supervisor state ---
    private val policy = com.smartyourtv.housecameras.core.RestartPolicy()
    private val meter get() = com.smartyourtv.housecameras.core.LiveState.meter(cam.name)
    private var connectStartedAt: Long = 0
    private var playerStartedAt: Long = 0
    private var firstFrameSeen = false
    private var lastPosition: Long = -1
    private var stillSamples = 0
    private var resolveTriedInSlowLane = false
    private var photosMode = false

    private fun status(s: String) {
        val st = com.smartyourtv.housecameras.core.LiveState.state(cam.name)
        st.stateWord = s; st.host = cam.host
        st.identityAnchor = cam.identity.anchorDescription()
        st.sourceLabel = when { relay.active() -> "Relay"; photosMode -> "Photos"; else -> "Direct" }
        st.lastFrameAt = lastGoodFrameAt
        st.restartsThisHour = policy.restartsThisHour()
        st.roadWord = meter.quality()
        onStatus(s, lastGoodFrameAt)
    }

    private fun noteFrame() {
        val now = System.currentTimeMillis()
        lastGoodFrameAt = now
        meter.frame()
        val st = com.smartyourtv.housecameras.core.LiveState.state(cam.name)
        st.lastFrameAt = now; st.roadWord = meter.quality()
    }

    private fun updateBreakerMessage() {
        val now = System.currentTimeMillis()
        com.smartyourtv.housecameras.core.LiveState.breakerMessage =
            if (com.smartyourtv.housecameras.core.Breaker.tripped(now))
                "Road is bad right now — streams will return on their own."
            else ""
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopped) return
            try { watchdogTick() } catch (_: Throwable) {}
            handler.postDelayed(this, 2000)
        }
    }

    private fun watchdogTick() {
        val p = player ?: return
        val now = System.currentTimeMillis()
        if (!firstFrameSeen) {
            if (playerStartedAt > 0 && now - playerStartedAt > 15_000 && !retryPending) {
                scheduleRetry("no picture after 15 s")
            }
            return
        }
        val pos = try { p.currentPosition } catch (_: Exception) { -1 }
        if (pos >= 0 && pos == lastPosition && p.playWhenReady && !retryPending) {
            if (++stillSamples >= 3) {
                // Picture frozen ~6 s: a stall, whatever the player thinks.
                stillSamples = 0
                meter.stallBegin()
                com.smartyourtv.housecameras.core.Breaker.reportStall(cam.name, now)
                updateBreakerMessage()
                com.smartyourtv.housecameras.core.Reports.event(ctx,
                    "STALL: ${cam.name} — picture froze; restarting stream")
                scheduleRetry("picture froze")
            }
        } else {
            if (pos != lastPosition) { stillSamples = 0; noteFrame() }
        }
        lastPosition = pos
    }

    fun attachToPlayerView(view: PlayerView, fallbackImage: android.widget.ImageView? = null) {
        viewRef = view
        fallbackImageRef = fallbackImage
        attempts = 0
        status("Connecting…")
        handler.removeCallbacks(watchdog); handler.postDelayed(watchdog, 2000)
        // CONTAINMENT (v1.0.4): before any player starts, pre-flight the
        // stream's H.265 SPS through the same parser Media3 will run.
        // A stream that breaks the parser never starts a player — it
        // can never take the app down. Inconclusive checks proceed.
        thread(name = "sps-guard-${cam.name}") {
            val safe = try { SpsGuard.streamIsSafe(cam, source, relay) } catch (_: Throwable) { true }
            handler.post {
                if (stopped) return@post
                if (!safe) onVetoed() else startPlayer()
            }
        }
    }

    /** A stream the video parser can't handle. Degrade instead of
     *  dying: the ingest ladder's last resort — photo polling from the
     *  camera's snapshot address (LAN only; the relay serves no
     *  snapshots). Only if photos also fail does the tile settle on
     *  the plain error message. */
    private fun onVetoed() {
        val img = fallbackImageRef
        val snapPath = Templates.forFamily(cam.family).snapshot
        if (img != null && !relay.active() && snapPath.isNotEmpty() && cam.host.isNotEmpty()) {
            photosMode = true
            viewRef?.visibility = android.view.View.GONE
            img.visibility = android.view.View.VISIBLE
            status("This camera's video format isn't supported — showing photos instead…")
            val snapSource = CameraSource(kind = "snapshot", path = snapPath, port = 80,
                scheme = "http", via = "snapshot-fallback")
            startSnapshotPolling(img, snapSource, vetoFallback = true)
        } else {
            status("This camera's picture format isn't supported by the player yet — " +
                     "your other cameras still work.")
        }
    }

    private fun startPlayer() {
        if (stopped) return
        val view = viewRef ?: return
        try {
        try { player?.release() } catch (_: Exception) {}
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (steady) { if (singleView) 4_000 else 10_000 } else 500,
                if (steady) { if (singleView) 6_000 else 15_000 } else 2_000,
                if (steady) 1_000 else 250,
                if (steady) 2_000 else 500)
            .build()
        val p = ExoPlayer.Builder(ctx).setLoadControl(loadControl).build()
        connectStartedAt = System.currentTimeMillis()
        playerStartedAt = connectStartedAt
        firstFrameSeen = false; lastPosition = -1; stillSamples = 0
        player = p
        view.player = p
        view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        view.useController = false
        val url = cam.streamUrl(source, relay)
        val item = MediaItem.fromUri(url)
        val factory = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(8000)
        // Relay auth rides in the URI user-info (scout:<token>@), built
        // by Camera.streamUrl — the form verified against the relay.
        try { p.setMediaSource(factory.createMediaSource(item)) }
        catch (_: Exception) { p.setMediaItem(item) }
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                attempts = 0
                firstFrameSeen = true
                policy.onFrame()
                meter.connected(System.currentTimeMillis() - connectStartedAt)
                com.smartyourtv.housecameras.core.Breaker.reportHealthy(cam.name)
                updateBreakerMessage()
                noteFrame()
                status("Live")
            }
            override fun onPlayerError(error: PlaybackException) { scheduleRetry(error.errorCodeName) }
            override fun onPlaybackStateChanged(state: Int) {
                // A stream that simply ends (session stop / 2 h cap)
                // arrives here rather than as an error.
                if (state == Player.STATE_ENDED && lastGoodFrameAt > 0) scheduleRetry("ended")
            }
        })
        p.prepare(); p.playWhenReady = true
        status(if (attempts == 0) "Connecting…" else "Reconnecting…")
        } catch (t: Throwable) {
            // Containment: a player that fails to even start becomes an
            // error tile via the retry path, never a crash.
            scheduleRetry(t.javaClass.simpleName)
        }
    }

    private fun scheduleRetry(reason: String) {
        if (stopped || retryPending) return
        retryPending = true
        attempts++
        meter.reconnect()
        try { player?.release() } catch (_: Exception) {}
        player = null
        if (relay.active()) {
            if (attempts <= 22) {  // ~90 s of retries at 4 s intervals
                status("Connecting… a camera can take up to half a minute to appear after a session starts.")
                handler.postDelayed({ retryPending = false; startPlayer() }, 4000)
            } else {
                // The relay is STOPPED at rest — after the tolerance
                // window, a failure means no session is open, or the
                // session code is wrong/expired. Say that plainly —
                // and keep a quiet slow lane: a session may start later.
                status("Not connected — no viewing session is open, or the session code has expired. " +
                         "A session is started at home and gives you a fresh code each time.")
                handler.postDelayed({ retryPending = false; startPlayer() }, 60_000)
            }
        } else {
            val wasSlow = policy.inSlowLane
            val delay = policy.nextDelayMs() +
                com.smartyourtv.housecameras.core.Breaker.extraDelayMs(System.currentTimeMillis())
            if (policy.inSlowLane) {
                status("No signal — still trying every minute (last problem: $reason)")
                if (!wasSlow && !resolveTriedInSlowLane) {
                    // Burst spent with no picture: maybe the camera
                    // MOVED. Ask the resolver once per slow-lane entry.
                    resolveTriedInSlowLane = true
                    kotlin.concurrent.thread(name = "sup-resolve-${cam.name}") {
                        try {
                            val old = cam.host
                            com.smartyourtv.housecameras.core.Resolver.resolveOne(ctx, cam)
                            if (cam.host != old) {
                                onHostHealed?.invoke()
                                handler.post { if (!stopped) { retryPending = false; startPlayer() } }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else {
                status("No signal — trying again…")
            }
            handler.postDelayed({ retryPending = false; if (!stopped) startPlayer() }, delay)
        }
        updateBreakerMessage()
    }

    fun startSnapshotPolling(image: ImageView, snapSource: CameraSource = source, vetoFallback: Boolean = false) {
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        photosMode = true
        handler.removeCallbacks(watchdog)  // photo tiles don't run the player watchdog
        val startedAt = System.currentTimeMillis()
        var vetoReported = false
        // Candidate snapshot paths (template first, then the paths the
        // fleet is known to answer, then the generic library). The
        // first path that returns a real picture is remembered and
        // used directly from then on. Fetches answer the camera's
        // 401 challenge (Basic or Digest) with the stored credentials.
        val candidates = if (vetoFallback) HttpFetch.snapshotCandidates(snapSource.path)
                         else listOf(snapSource.path)
        var workingPath: String? = null
        var sawAuthRequired = false
        pollThread = thread(name = "snap-${cam.name}") {
            while (!stopped) {
                var gotFrame = false
                val pathsToTry = if (workingPath != null) listOf(workingPath!!) else candidates
                for (path in pathsToTry) {
                    if (stopped) break
                    val res = HttpFetch.get("http://${cam.host}$path",
                        cam.credentials.username, cam.credentials.password)
                    when (res.outcome) {
                        HttpFetch.Outcome.OK -> {
                            val bytes = res.bytes
                            val bmp = if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size) else null
                            if (bmp != null) {
                                workingPath = path; gotFrame = true
                                noteFrame()
                                Handler(Looper.getMainLooper()).post {
                                    if (!stopped) { image.setImageBitmap(bmp); status("Live (photos)") }
                                }
                                break
                            }
                        }
                        HttpFetch.Outcome.AUTH_REQUIRED -> sawAuthRequired = true
                        else -> { /* try the next candidate */ }
                    }
                }
                if (!gotFrame && !vetoFallback && !stopped) status("No signal")
                if (vetoFallback && !vetoReported && lastGoodFrameAt == 0L &&
                    System.currentTimeMillis() - startedAt > 10000) {
                    vetoReported = true
                    status(when {
                        sawAuthRequired && !cam.credentials.hasLogin() ->
                            "This camera's video format isn't supported by the player yet, and its photos " +
                            "need the camera's password — press and hold this tile and add the password in its settings."
                        sawAuthRequired ->
                            "This camera's video format isn't supported by the player yet, and the password " +
                            "saved for it didn't open its photos — press and hold this tile to check the password."
                        else ->
                            "This camera's video format isn't supported by the player yet, and photos " +
                            "aren't available either — your other cameras still work."
                    })
                }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun release() {
        stopped = true
        handler.removeCallbacks(watchdog)
        handler.removeCallbacksAndMessages(null)
        pollThread?.interrupt()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
