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

    fun attachToPlayerView(view: PlayerView) {
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
                lastGoodFrameAt = System.currentTimeMillis()
                onStatus("Live", lastGoodFrameAt)
            }
            override fun onPlayerError(error: PlaybackException) {
                if (relay.active()) {
                    // The relay is STOPPED at rest — a refused/failed
                    // connection usually means no session is open, or
                    // the session code is wrong/expired. Say that plainly.
                    onStatus("Not connected — no viewing session is open, or the session code has expired. " +
                             "A session is started at home and gives you a fresh code each time.", lastGoodFrameAt)
                } else {
                    onStatus("No signal — ${error.errorCodeName}", lastGoodFrameAt)
                }
            }
        })
        p.prepare(); p.playWhenReady = true
        onStatus("Connecting…", lastGoodFrameAt)
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
        pollThread?.interrupt()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
