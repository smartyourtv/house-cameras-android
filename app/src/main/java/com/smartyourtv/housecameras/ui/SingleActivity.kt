package com.smartyourtv.housecameras.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.media3.ui.PlayerView
import com.smartyourtv.housecameras.core.*

class SingleActivity : Activity() {
    private var player: CameraPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Store.load(this)
        val dvrChannel = intent.getIntExtra("dvrChannel", -1)
        val cam: Camera
        val isDvr = dvrChannel >= 0
        if (isDvr) {
            val src = CameraSource(kind = "rtsp", path = cfg.dvr.path(dvrChannel),
                port = cfg.dvr.port, via = "relay")
            cam = Camera(name = "dvr-${dvrChannel + 1}", host = cfg.dvr.host,
                family = "generic", main = src, sub = src,
                notes = "Van DVR channel ${dvrChannel + 1} — through the laptop relay")
        } else {
            val idx = intent.getIntExtra("index", 0)
            cam = cfg.cameras.getOrNull(idx) ?: run { finish(); return }
        }
        title = cam.name
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        // Amendment (Nov 6–19 set): an always-visible ALL CAMERAS
        // control — single view never traps the user in one tile.
        val allBtn = Button(this).apply {
            text = "ALL CAMERAS"
            setOnClickListener { finish() }
        }
        root.addView(allBtn)
        val status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 13f; text = "${cam.name} — starting…" }
        root.addView(status)
        val source = if (cam.main.path.isNotEmpty()) cam.main else cam.sub
        // In Away mode the relay serves the main stream at /<slot name>
        // (streamUrl builds that); snapshot polling is LAN-only.
        if (!isDvr && !cfg.relay.active() && (source.kind == "snapshot" || source.kind == "mjpeg")) {
            val img = ImageView(this)
            root.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            player = CameraPlayer(this, cam, source, cfg.relay, singleView = true, steady = cfg.steadyPicture) { s, _ -> runOnUiThread { status.text = "${cam.name} — $s" } }
            player!!.startSnapshotPolling(img)
        } else {
            val frame = android.widget.FrameLayout(this)
            val pv = PlayerView(this)
            val fallbackImg = ImageView(this).apply { visibility = android.view.View.GONE }
            frame.addView(pv, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            frame.addView(fallbackImg, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            player = if (isDvr) CameraPlayer(this, cam, source, cfg.relay,
                singleView = true, steady = cfg.steadyPicture,
                // Pack J: single view builds the DVR player with
                // EXACTLY the grid's parameters — the honest
                // feed-down message, the feed probe, the "Relay"
                // label, and identity management OFF, so the
                // resolver can never repoint the DVR pseudo-camera.
                sourceLabelOverride = "Relay",
                downMessage = "DVR feed is down — the van DVR's link at home is off right now. " +
                              "The app keeps checking and the picture will appear on its own when the feed returns.",
                feedProbe = true, identityManaged = false
            ) { s, _ -> runOnUiThread { status.text = "${cam.name} — $s" } }
            else CameraPlayer(this, cam, source, cfg.relay, singleView = true, steady = cfg.steadyPicture) { s, _ -> runOnUiThread { status.text = "${cam.name} — $s" } }
            player!!.attachToPlayerView(pv, fallbackImg)
        }
        val hint = TextView(this).apply {
            setTextColor(Color.GRAY); textSize = 12f
            text = if (isDvr) "This DVR channel comes through the laptop relay at home. Back returns to all cameras."
                   else "This is the camera's main (clearest) picture. Back returns to all cameras."
        }
        root.addView(hint)
        setContentView(root)
    }

    override fun onPause() { super.onPause(); player?.release(); player = null }
}
