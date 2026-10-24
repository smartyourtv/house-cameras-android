package com.smartyourtv.housecameras.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.ui.PlayerView
import com.smartyourtv.housecameras.BuildConfig
import com.smartyourtv.housecameras.core.*
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var cfg: AppConfig
    private val players = mutableListOf<CameraPlayer>()
    private lateinit var grid: GridLayout
    private lateinit var modeLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reports.installCrashHandler(this)
        cfg = Store.load(this)
        title = "House Cameras"
        buildUi()
        checkForUpdateQuietly()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        modeLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f }
        bar.addView(modeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun menuBtn(label: String, act: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { act() }
        }
        bar.addView(menuBtn("Find cameras") { startActivity(Intent(this, DiscoveryActivity::class.java)) })
        bar.addView(menuBtn("Add") { openEdit(-1) })
        bar.addView(menuBtn("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        bar.addView(menuBtn("Status") { startActivity(Intent(this, DiagnosticsActivity::class.java)) })
        root.addView(bar)
        val honest = TextView(this).apply {
            setTextColor(Color.LTGRAY); textSize = 12f; setPadding(dp(8), 0, dp(8), dp(4))
            text = "Some cameras can't be added: ones that only work through a company cloud app " +
                   "(like Ring, Blink, or Nest) don't send a picture on your home network, so there is nothing for this app to show."
            visibility = View.GONE
        }
        honest.id = 991; root.addView(honest)
        val scroll = ScrollView(this)
        grid = GridLayout(this).apply { columnCount = 2 }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshModeLabel()
    }

    private fun refreshModeLabel() {
        modeLabel.text = if (cfg.relay.active())
            "Away mode — through the home relay (${cfg.relay.host})"
        else "At home — direct from your cameras"
    }

    override fun onResume() {
        super.onResume()
        cfg = Store.load(this)
        refreshModeLabel()
        buildGrid()
    }

    override fun onPause() {
        super.onPause()
        players.forEach { it.release() }; players.clear()
    }

    private fun buildGrid() {
        grid.removeAllViews()
        val enabled = cfg.cameras.filter { it.enabled }
        val cellW = (resources.displayMetrics.widthPixels / 2) - dp(12)
        val cellH = (cellW * 0.72).toInt()
        for ((idx, cam) in cfg.cameras.withIndex()) {
            if (!cam.enabled) continue
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4))
                setBackgroundColor(Color.rgb(24, 24, 24))
            }
            val status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; text = "${cam.name} — starting…" }
            val source = if (cam.sub.path.isNotEmpty()) cam.sub else cam.main
            if (source.kind == "snapshot" || source.kind == "mjpeg") {
                val img = ImageView(this)
                cell.addView(img, LinearLayout.LayoutParams(cellW, cellH))
                val cp = CameraPlayer(this, cam, source, cfg.relay) { s, _ ->
                    runOnUiThread { status.text = "${cam.name} — $s" }
                }
                players.add(cp); cp.startSnapshotPolling(img)
            } else if (cam.host.isEmpty() && !cfg.relay.active()) {
                val tv = TextView(this).apply {
                    setTextColor(Color.GRAY); gravity = Gravity.CENTER; textSize = 13f
                    text = "Not set up yet — this camera hasn't been found on the network."
                }
                cell.addView(tv, LinearLayout.LayoutParams(cellW, cellH))
                status.text = "${cam.name} — not set up"
            } else {
                val pv = PlayerView(this)
                cell.addView(pv, LinearLayout.LayoutParams(cellW, cellH))
                val cp = CameraPlayer(this, cam, source, cfg.relay) { s, _ ->
                    runOnUiThread { status.text = "${cam.name} — $s" }
                }
                players.add(cp); cp.attachToPlayerView(pv)
            }
            cell.addView(status)
            cell.setOnClickListener { openSingle(idx) }
            cell.setOnLongClickListener { openEdit(idx); true }
            grid.addView(cell)
        }
        if (enabled.isEmpty()) {
            val tv = TextView(this).apply {
                setTextColor(Color.WHITE); textSize = 16f; setPadding(dp(16), dp(16), dp(16), dp(16))
                text = "No cameras are turned on yet. Tap Add to add one, or Find cameras to search your home network."
            }
            grid.addView(tv)
        }
    }

    private fun openSingle(index: Int) {
        val i = Intent(this, SingleActivity::class.java); i.putExtra("index", index); startActivity(i)
    }
    private fun openEdit(index: Int) {
        val i = Intent(this, EditCameraActivity::class.java); i.putExtra("index", index); startActivity(i)
    }

    private fun checkForUpdateQuietly() {
        thread(name = "updater-check") {
            val avail = Updater.check(BuildConfig.VERSION_CODE) ?: return@thread
            // One subtle notice per version (standing law).
            if (cfg.lastNotifiedVersionCode >= avail.versionCode) return@thread
            cfg.lastNotifiedVersionCode = avail.versionCode
            Store.save(this, cfg)
            runOnUiThread {
                Toast.makeText(this,
                    "House Cameras update ${avail.versionName} is available — open Settings to install it.",
                    Toast.LENGTH_LONG).show()
            }
        }
    }
}
