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
        val idx = intent.getIntExtra("index", 0)
        val cam = cfg.cameras.getOrNull(idx) ?: run { finish(); return }
        title = cam.name
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 13f; text = "${cam.name} — starting…" }
        root.addView(status)
        val source = if (cam.main.path.isNotEmpty()) cam.main else cam.sub
        // In Away mode the relay serves the main stream at /<slot name>
        // (streamUrl builds that); snapshot polling is LAN-only.
        if (!cfg.relay.active() && (source.kind == "snapshot" || source.kind == "mjpeg")) {
            val img = ImageView(this)
            root.addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            player = CameraPlayer(this, cam, source, cfg.relay) { s, _ -> runOnUiThread { status.text = "${cam.name} — $s" } }
            player!!.startSnapshotPolling(img)
        } else {
            val pv = PlayerView(this)
            root.addView(pv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            player = CameraPlayer(this, cam, source, cfg.relay) { s, _ -> runOnUiThread { status.text = "${cam.name} — $s" } }
            player!!.attachToPlayerView(pv)
        }
        val hint = TextView(this).apply {
            setTextColor(Color.GRAY); textSize = 12f
            text = "This is the camera's main (clearest) picture. Back returns to all cameras."
        }
        root.addView(hint)
        setContentView(root)
    }

    override fun onPause() { super.onPause(); player?.release(); player = null }
}
