package com.smartyourtv.housecameras.ui

import android.app.Activity
import android.net.wifi.WifiManager
import android.os.Bundle
import android.widget.*
import com.smartyourtv.housecameras.core.*
import kotlin.concurrent.thread

/** Runs all four discovery methods and merges the results.
 *  Found cameras can be added with one tap (no credentials are asked
 *  for here beyond what Edit asks later, on this phone only). */
class DiscoveryActivity : Activity() {
    private val found = mutableListOf<FoundHost>()
    private lateinit var listText: TextView
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Find cameras"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (12 * resources.displayMetrics.density).toInt(); root.setPadding(pad, pad, pad, pad)
        val intro = TextView(this).apply {
            textSize = 14f
            text = "This searches your home network four different ways at once. It takes about half a minute. " +
                   "Things that are found but aren't cameras are listed too — they are only looked at, never changed."
        }
        root.addView(intro)
        val btn = Button(this).apply { text = "Start searching" }
        listText = TextView(this).apply { textSize = 14f }
        btn.setOnClickListener { runDiscovery(btn) }
        root.addView(btn)
        val scroll = ScrollView(this); scroll.addView(listText); root.addView(scroll)
        setContentView(root)
    }

    private fun runDiscovery(btn: Button) {
        btn.isEnabled = false
        listText.text = "Searching…"
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val lock = wifi.createMulticastLock("housecameras-discovery").apply { setReferenceCounted(false); acquire() }
        thread(name = "discovery") {
            try {
                val prefix = Discovery.localSubnetPrefix(this)
                val merged = Discovery.merge(
                    Discovery.onvif(), Discovery.hip2pVscp(), Discovery.lanSweep(prefix))
                found.clear(); found.addAll(merged)
                runOnUiThread {
                    btn.isEnabled = true
                    if (merged.isEmpty()) {
                        listText.text = "Nothing answered. Make sure the phone is on the same home Wi-Fi as the cameras, then try again."
                    } else {
                        listText.text = merged.joinToString("\n") {
                            "${it.host} — ${it.family ?: "unknown"} (found by ${it.via}) ${it.detail}"
                        }
                        for (fh in merged) addAddButton(fh)
                    }
                }
            } finally { try { lock.release() } catch (_: Exception) {} }
        }
    }

    private fun addAddButton(fh: FoundHost) {
        val cfg = Store.load(this)
        if (cfg.cameras.any { it.host == fh.host }) return
        val b = Button(this).apply { text = "Add ${fh.host} as a camera" }
        b.setOnClickListener {
            val c2 = Store.load(this@DiscoveryActivity)
            c2.cameras.add(Camera(name = "camera-${c2.cameras.size + 1}", host = fh.host,
                family = fh.family ?: "generic"))
            Store.save(this@DiscoveryActivity, c2)
            Toast.makeText(this@DiscoveryActivity,
                "Added. Open it from the main screen (press and hold) to enter its password and find its picture.",
                Toast.LENGTH_LONG).show()
            b.isEnabled = false
        }
        root.addView(b)
    }
}
