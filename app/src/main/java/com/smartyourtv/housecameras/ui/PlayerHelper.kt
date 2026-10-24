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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.concurrent.thread

/** Plays one camera source. RTSP goes to ExoPlayer with letterbox
 *  (RESIZE_MODE_FIT — largest picture, true shape, no stretch/crop).
 *  Snapshot sources poll JPEGs into an ImageView (FIT_CENTER = same
 *  letterbox rule). Status is reported by last-good-frame freshness. */
class CameraPlayer(
    private val ctx: Context,
    private val cam: Camera,
    private val source: CameraSource,
    private val relay: RelayConfig,
    private val onStatus: (String, Long) -> Unit
) {
    var player: ExoPlayer? = null
    private var pollThread: Thread? = null
    @Volatile var stopped = false
    var lastGoodFrameAt: Long = 0

    // Reconnect-with-backoff (session tolerance, relay spec): a relay
    // path may report not-ready for up to ~30 s after a session starts,
    // and a stream ends outright at the 2 h cap / session stop. Relay
    // mode retries every 4 s for ~90 s before declaring "not connected";
    // LAN mode retries a handful of times with growing waits. A fresh
    // first frame resets the counter.
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0
    private var retryPending = false
    private var viewRef: PlayerView? = null

    fun attachToPlayerView(view: PlayerView) {
        viewRef = view
        attempts = 0
        startPlayer()
    }

    private fun startPlayer() {
        if (stopped) return
        val view = viewRef ?: return
        try { player?.release() } catch (_: Exception) {}
        val p = ExoPlayer.Builder(ctx).build()
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
                lastGoodFrameAt = System.currentTimeMillis()
                onStatus("Live", lastGoodFrameAt)
            }
            override fun onPlayerError(error: PlaybackException) { scheduleRetry(error.errorCodeName) }
            override fun onPlaybackStateChanged(state: Int) {
                // A stream that simply ends (session stop / 2 h cap)
                // arrives here rather than as an error.
                if (state == Player.STATE_ENDED && lastGoodFrameAt > 0) scheduleRetry("ended")
            }
        })
        p.prepare(); p.playWhenReady = true
        onStatus(if (attempts == 0) "Connecting…" else "Reconnecting…", lastGoodFrameAt)
    }

    private fun scheduleRetry(reason: String) {
        if (stopped || retryPending) return
        retryPending = true
        attempts++
        if (relay.active()) {
            if (attempts <= 22) {  // ~90 s of retries at 4 s intervals
                onStatus("Connecting… a camera can take up to half a minute to appear after a session starts.", lastGoodFrameAt)
                handler.postDelayed({ retryPending = false; startPlayer() }, 4000)
            } else {
                // The relay is STOPPED at rest — after the tolerance
                // window, a failure means no session is open, or the
                // session code is wrong/expired. Say that plainly.
                onStatus("Not connected — no viewing session is open, or the session code has expired. " +
                         "A session is started at home and gives you a fresh code each time.", lastGoodFrameAt)
            }
        } else {
            if (attempts <= 5) {
                val waitMs = minOf(2000L * (1 shl (attempts - 1)), 30000L)
                onStatus("No signal — trying again…", lastGoodFrameAt)
                handler.postDelayed({ retryPending = false; startPlayer() }, waitMs)
            } else {
                onStatus("No signal — $reason", lastGoodFrameAt)
            }
        }
    }

    fun startSnapshotPolling(image: ImageView) {
        image.scaleType = ImageView.ScaleType.FIT_CENTER
        pollThread = thread(name = "snap-${cam.name}") {
            while (!stopped) {
                try {
                    val conn = URL("http://${cam.host}${source.path}").openConnection() as HttpURLConnection
                    conn.connectTimeout = 1500; conn.readTimeout = 2000
                    if (cam.credentials.hasLogin()) {
                        val basic = Base64.getEncoder().encodeToString(
                            "${cam.credentials.username}:${cam.credentials.password}".toByteArray())
                        conn.setRequestProperty("Authorization", "Basic $basic")
                    }
                    val bytes = conn.inputStream.readBytes()
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        lastGoodFrameAt = System.currentTimeMillis()
                        Handler(Looper.getMainLooper()).post {
                            if (!stopped) { image.setImageBitmap(bmp); onStatus("Live (photos)", lastGoodFrameAt) }
                        }
                    } else onStatus("No signal", lastGoodFrameAt)
                } catch (_: Exception) {
                    if (!stopped) onStatus("No signal", lastGoodFrameAt)
                }
                try { Thread.sleep(2000) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun release() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        pollThread?.interrupt()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
