package com.smartyourtv.housecameras.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.media3.ui.PlayerView
import com.smartyourtv.housecameras.BuildConfig
import com.smartyourtv.housecameras.core.*
import kotlin.concurrent.thread

/** The stage (v2 P2). Banked pattern: the stage comes first — tall,
 *  leading the layout — and the grids are exact-fill: HOUSE 3×2,
 *  DVR one row of 4, ALL 5×2. Tiles are D-pad focusable with a
 *  visible focus frame; OK/tap opens the big view, Back returns.
 *  Identity resolution runs before every stage build (P1), so a
 *  camera that moved is found by who it is before any tile starts. */
class MainActivity : Activity() {
    private lateinit var cfg: AppConfig
    private val players = mutableListOf<CameraPlayer>()
    private lateinit var stage: LinearLayout
    private lateinit var modeLabel: TextView
    private lateinit var banner: TextView
    private var view: StageSpec.View = StageSpec.HOUSE
    private var resolving = false
    private val uiHandler = Handler(Looper.getMainLooper())
    /** Focus memory (P4, rehearsal item 4 watch): stage rebuilds
     *  (resume, resolver return) restore the tile/bar position the
     *  remote was on instead of dropping focus to the first tile. */
    private var lastFocusKey: String = ""
    private var roadBadSince: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Reports.installCrashHandler(this)
        cfg = Store.load(this)
        view = StageSpec.byName(cfg.homeView)
        title = "House Cameras"
        buildUi()
        checkForUpdateQuietly()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        modeLabel = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        bar.addView(modeLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (v in StageSpec.VIEWS) {
            val b = Button(this).apply {
                text = v.label
                tag = "bar:${v.label}"
                setOnClickListener { switchView(v) }
                setOnFocusChangeListener { fv, has -> if (has) lastFocusKey = fv.tag as String }
            }
            bar.addView(b)
        }
        fun menuBtn(label: String, act: () -> Unit) = Button(this).apply {
            text = label; tag = "bar:$label"; setOnClickListener { act() }
            setOnFocusChangeListener { fv, has -> if (has) lastFocusKey = fv.tag as String }
        }
        bar.addView(menuBtn("Find cameras") { startActivity(Intent(this, DiscoveryActivity::class.java)) })
        bar.addView(menuBtn("Add") { openEdit(-1) })
        bar.addView(menuBtn("Settings") { startActivity(Intent(this, SettingsActivity::class.java)) })
        bar.addView(menuBtn("Status") { startActivity(Intent(this, DiagnosticsActivity::class.java)) })
        root.addView(bar)
        banner = TextView(this).apply {
            setTextColor(Color.rgb(255, 200, 90)); textSize = 13f
            setPadding(dp(8), dp(2), dp(8), dp(2)); visibility = View.GONE
        }
        root.addView(banner)
        stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        refreshModeLabel()
    }

    private fun refreshModeLabel() {
        modeLabel.text = if (cfg.relay.active())
            "Away — through the home relay"
        else "At home — direct from your cameras"
    }

    private fun switchView(v: StageSpec.View) {
        if (v == view && players.isNotEmpty()) return
        view = v
        cfg.homeView = v.name
        Store.save(this, cfg)
        buildStage()
    }

    private val bannerTick = object : Runnable {
        override fun run() {
            // Banner fires on a breaker trip OR a sustained Bad road
            // (60 s) — staggered stalls that never trip the breaker
            // still owe the grid a plain road signal (rehearsal item 6).
            val now = System.currentTimeMillis()
            val roadBad = LiveState.roadSummary() == "Bad"
            if (roadBad) { if (roadBadSince == 0L) roadBadSince = now }
            else roadBadSince = 0
            val sustainedBad = roadBad && now - roadBadSince >= 60_000
            val msg = when {
                LiveState.breakerMessage.isNotEmpty() -> LiveState.breakerMessage
                sustainedBad -> "Road is bad right now — streams will return on their own."
                else -> ""
            }
            banner.text = msg
            banner.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
            uiHandler.postDelayed(this, 3000)
        }
    }

    override fun onResume() {
        super.onResume()
        cfg = Store.load(this)
        view = StageSpec.byName(cfg.homeView)
        refreshModeLabel()
        players.forEach { it.release() }; players.clear()
        stage.removeAllViews()
        uiHandler.removeCallbacks(bannerTick); uiHandler.post(bannerTick)
        if (resolving) return
        resolving = true
        Resolver.resolveAsync(this, cfg) { changed ->
            runOnUiThread {
                resolving = false
                if (changed) Store.save(this, cfg)
                buildStage()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(bannerTick)
        players.forEach { it.release() }; players.clear()
    }

    /** The DVR pseudo-cameras: built in memory per view from the
     *  feed config — never saved into the camera list, so identity
     *  and resolver logic never touch them. */
    private fun dvrCameras(): List<Camera> = (0 until cfg.dvr.channels).map { ch ->
        val src = CameraSource(kind = "rtsp", path = cfg.dvr.path(ch),
            port = cfg.dvr.port, via = "relay")
        Camera(name = "dvr-${ch + 1}", host = cfg.dvr.host, family = "generic",
            main = src, sub = src, notes = "Van DVR channel ${ch + 1} — through the laptop relay")
    }

    private data class Tile(val cam: Camera, val cfgIndex: Int, val dvrChannel: Int)

    private fun tilesForView(): List<Tile?> {
        val house = cfg.cameras.withIndex().filter { it.value.enabled }
            .map { Tile(it.value, it.index, -1) }
        val dvr = dvrCameras().mapIndexed { ch, cam -> Tile(cam, -1, ch) }
        val slots: List<Tile> = when (view) {
            StageSpec.DVR -> dvr
            StageSpec.ALL -> (house.take(6) + dvr).take(StageSpec.tiles(StageSpec.ALL))
            else -> house.take(StageSpec.tiles(StageSpec.HOUSE))
        }
        val out = mutableListOf<Tile?>()
        out.addAll(slots)
        while (out.size < StageSpec.tiles(view)) out.add(null)
        return out
    }

    private fun buildStage() {
        players.forEach { it.release() }; players.clear()
        stage.removeAllViews()
        val tiles = tilesForView()
        var firstTile: View? = null
        for (r in 0 until view.rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            stage.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            for (c in 0 until view.cols) {
                val tile = tiles[r * view.cols + c]
                val cell = buildTile(tile)
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                if (firstTile == null && tile != null) firstTile = cell
            }
        }
        // Restore remembered focus (a tile key from before the
        // rebuild), else land on the first tile on TV.
        val target = if (lastFocusKey.isNotEmpty())
            window.decorView.findViewWithTag<View>(lastFocusKey) ?: firstTile else firstTile
        if (isTelevision(this)) target?.post { target.requestFocus() }
    }

    private fun buildTile(tile: Tile?): View {
        val wrap = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(10, 10, 10))
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        val inner = FrameLayout(this)
        wrap.addView(inner, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (tile == null) {
            inner.addView(TextView(this).apply {
                setTextColor(Color.DKGRAY); gravity = Gravity.CENTER; textSize = 12f
                text = "Nothing here yet"
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return wrap
        }
        val cam = tile.cam
        val status = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; maxLines = 2
            setBackgroundColor(Color.argb(160, 0, 0, 0))
            setPadding(dp(6), dp(2), dp(6), dp(2))
            text = "${cam.name} — starting…"
        }
        val statusLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM
        }
        val isDvr = tile.dvrChannel >= 0
        val source = if (isDvr) cam.main
                     else if (cam.sub.path.isNotEmpty()) cam.sub else cam.main
        val relayedHouse = cfg.relay.active() && !isDvr &&
            cam.name.matches(Regex("house-[1-6]"))
        when {
            cfg.relay.active() && !relayedHouse -> {
                inner.addView(TextView(this).apply {
                    setTextColor(Color.GRAY); gravity = Gravity.CENTER; textSize = 13f
                    text = if (isDvr) "The DVR isn't part of remote viewing."
                           else "Not part of remote viewing — the home relay carries house-1 to house-6 only."
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                status.text = "${cam.name} — at-home only"
            }
            !cfg.relay.active() && !isDvr && (source.kind == "snapshot" || source.kind == "mjpeg") -> {
                val img = ImageView(this)
                inner.addView(img, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val cp = CameraPlayer(this, cam, source, cfg.relay,
                    steady = cfg.steadyPicture,
                    onHostHealed = { Store.save(this, cfg) }) { s, _ ->
                    runOnUiThread { status.text = "${cam.name} — $s" }
                }
                players.add(cp); cp.startSnapshotPolling(img)
            }
            cam.host.isEmpty() && !cfg.relay.active() -> {
                inner.addView(TextView(this).apply {
                    setTextColor(Color.GRAY); gravity = Gravity.CENTER; textSize = 13f
                    text = "Not set up yet — this camera hasn't been found on the network."
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                status.text = "${cam.name} — not set up"
            }
            else -> {
                val pv = PlayerView(this)
                val fallbackImg = ImageView(this).apply { visibility = View.GONE }
                inner.addView(pv, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                inner.addView(fallbackImg, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val cp = if (isDvr) CameraPlayer(this, cam, source, cfg.relay,
                    steady = cfg.steadyPicture,
                    sourceLabelOverride = "Relay",
                    downMessage = "DVR feed is down — the van DVR's link at home is off right now. " +
                                  "The app keeps checking and the picture will appear on its own when the feed returns.",
                    feedProbe = true, identityManaged = false
                ) { s, _ -> runOnUiThread { status.text = "${cam.name} — $s" } }
                else CameraPlayer(this, cam, source, cfg.relay,
                    steady = cfg.steadyPicture,
                    onHostHealed = { Store.save(this, cfg) }) { s, _ ->
                    runOnUiThread { status.text = "${cam.name} — $s" }
                }
                players.add(cp); cp.attachToPlayerView(pv, fallbackImg)
            }
        }
        inner.addView(status, statusLp)
        wrap.isFocusable = true
        wrap.isClickable = true
        wrap.tag = "tile:${cam.name}"
        wrap.setOnFocusChangeListener { v, has ->
            v.setBackgroundColor(if (has) Color.rgb(20, 83, 45) else Color.rgb(10, 10, 10))
            if (has) lastFocusKey = v.tag as String
        }
        wrap.setOnClickListener {
            if (isDvr) openDvr(tile.dvrChannel) else openSingle(tile.cfgIndex)
        }
        wrap.setOnLongClickListener {
            if (!isDvr) { openEdit(tile.cfgIndex); true } else false
        }
        return wrap
    }

    private fun openSingle(index: Int) {
        val i = Intent(this, SingleActivity::class.java); i.putExtra("index", index); startActivity(i)
    }
    private fun openDvr(channel: Int) {
        val i = Intent(this, SingleActivity::class.java)
        i.putExtra("dvrChannel", channel); startActivity(i)
    }
    private fun openEdit(index: Int) {
        val i = Intent(this, EditCameraActivity::class.java); i.putExtra("index", index); startActivity(i)
    }

    private fun checkForUpdateQuietly() {
        thread(name = "updater-check") {
            val avail = Updater.check(BuildConfig.VERSION_CODE) ?: return@thread
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
