package com.smartyourtv.housecameras.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.core.content.FileProvider
import com.smartyourtv.housecameras.BuildConfig
import com.smartyourtv.housecameras.core.*
import kotlin.concurrent.thread

/** Diagnostics/status view + one-tap self-test + crash-report viewer
 *  (standing laws). Freshness-based: per-camera status comes from the
 *  age of the last good frame the app has seen, not from guesses. */
class DiagnosticsActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var output: TextView

    /** Pack G M6: the RoadMeter numbers the engine already
     *  collects — stalls, stall time, reconnects, connect time —
     *  shown, not just gathered. */
    private fun roadStats(name: String): String {
        val m = LiveState.meters[name] ?: return ""
        val connect = if (m.lastConnectMs >= 0) " — connect ${m.lastConnectMs}ms" else ""
        return " — stalls ${m.stallCount()} (${m.stallTotalMs() / 1000}s) — reconnects ${m.reconnectCount()}$connect"
    }

    /** Future-proofing amendment: what the camera itself reported
     *  it can do — asked, not assumed; advertised ≠ proven. */
    private fun capsText(c: Camera): String {
        val caps = c.capabilities
        if (caps.learnedAt == 0L) return " — can do: not asked yet"
        val codec = if (caps.mainCodec.isNotEmpty()) " — main picture ${caps.mainCodec}" else ""
        val ka = if (c.keepalive.isNotEmpty()) " — keep-alive ${c.keepalive}" else ""
        return " — can do: ptz ${caps.ptz.ifEmpty { "absent" }} · events ${caps.events.ifEmpty { "absent" }}$codec$ka"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Status & help"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (12 * resources.displayMetrics.density).toInt(); root.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)

        val cfg = Store.load(this)
        root.addView(TextView(this).apply {
            textSize = 15f
            text = buildString {
                appendLine("House Cameras ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Mode: ${if (cfg.relay.active()) "Away — through relay ${cfg.relay.host}" else "At home — direct"}")
                appendLine("Cameras: ${cfg.cameras.count { it.enabled }} turned on of ${cfg.cameras.size}")
                // Pack G M9: the box section carries the view/buffer
                // settings and the updater state, not only cameras.
                appendLine("Steady picture (deep buffer for a shaky road): ${if (cfg.steadyPicture) "on" else "off"}")
                appendLine("Home view: ${cfg.homeView}")
                appendLine("App updates: this is ${BuildConfig.VERSION_NAME}; " +
                    if (cfg.lastNotifiedVersionCode > BuildConfig.VERSION_CODE)
                        "a newer version was offered (code ${cfg.lastNotifiedVersionCode})"
                    else "no newer version has been offered")
                for (c in cfg.cameras) {
                    appendLine("• ${c.name} — ${c.host.ifEmpty { "no address" }} — ${Templates.forFamily(c.family).label}" +
                        " — picture by: ${c.main.via.ifEmpty { "not found yet" }}")
                }
                val repairs = Store.repairHistory(this@DiagnosticsActivity)
                if (repairs.isNotEmpty())
                    appendLine("Self-repairs: ${repairs.joinToString("; ")}")
                if (Crypto.unlockFailures > 0)
                    appendLine("Stored logins: ${Crypto.unlockFailures} could not be unlocked on this device — re-enter the camera password in its settings.")
            }
        })

        // v2 live section: what every tile is doing RIGHT NOW, from
        // frame freshness — plus the road word and the event log.
        root.addView(TextView(this).apply {
            textSize = 15f
            text = buildString {
                appendLine("Road: ${LiveState.roadSummary()}")
                if (LiveState.breakerMessage.isNotEmpty()) appendLine(LiveState.breakerMessage)
                val cfgNames = cfg.cameras.map { it.name }.toSet()
                for ((name, st) in LiveState.cams) {
                    if (name in cfgNames) continue
                    // Tiles that aren't saved cameras (the DVR row):
                    val age = if (st.lastFrameAt == 0L) "no picture yet"
                        else "${(System.currentTimeMillis() - st.lastFrameAt) / 1000}s ago"
                    appendLine("• $name — ${st.stateWord} — by ${st.sourceLabel} — at ${st.host} " +
                               "— last picture $age — road ${st.roadWord} — restarts this hour: ${st.restartsThisHour}" +
                               roadStats(name))
                }
                for (c in cfg.cameras) {
                    if (!c.enabled) continue
                    val st = LiveState.cams[c.name]
                    if (st == null) {
                        appendLine("• ${c.name} — not started yet — at ${c.host.ifEmpty { "no address" }} " +
                                   "(identity: ${c.identity.anchorDescription()})")
                    } else {
                        val age = if (st.lastFrameAt == 0L) "no picture yet"
                            else "${(System.currentTimeMillis() - st.lastFrameAt) / 1000}s ago"
                        appendLine("• ${c.name} — ${st.stateWord} — by ${st.sourceLabel} — at ${st.host} " +
                                   "(identity: ${st.identityAnchor}) — last picture $age — road ${st.roadWord} — restarts this hour: ${st.restartsThisHour}" +
                                   roadStats(c.name) +
                                   if (st.identityNote.isNotEmpty()) " — ${st.identityNote}" else "" +
                                   capsText(c))
                    }
                }
            }
        })
        val events = Reports.events(this, 15)
        if (events.isNotEmpty()) {
            root.addView(TextView(this).apply { text = "Recent events (heals, stalls, repairs)"; textSize = 17f })
            root.addView(TextView(this).apply {
                textSize = 12f; text = events.joinToString("\n")
            })
        }

        output = TextView(this).apply { textSize = 14f }
        val testBtn = Button(this).apply { text = "Run the self-test" }
        testBtn.setOnClickListener {
            output.text = "Running…"
            thread(name = "selftest") {
                val results = SelfTest.run(this@DiagnosticsActivity)
                runOnUiThread {
                    output.text = results.joinToString("\n") {
                        (if (it.ok) "✓ " else "✗ ") + it.name + " — " + it.detail
                    }
                }
            }
        }
        root.addView(testBtn); root.addView(output)

        root.addView(TextView(this).apply { text = "Problem reports"; textSize = 17f })
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "If the app ever crashes, a report is saved here and as a file in your Downloads folder. " +
                   "Reports are never sent anywhere by themselves — you choose when to share one."
        })
        refreshReports()
    }

    private fun refreshReports() {
        val files = Reports.list(this)
        if (files.isEmpty()) {
            root.addView(TextView(this).apply { text = "No reports. That's good."; textSize = 14f })
            return
        }
        for (f in files.take(20)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply { text = f.name; textSize = 13f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val share = Button(this).apply { text = "Share" }
            share.setOnClickListener {
                val uri: Uri = FileProvider.getUriForFile(this@DiagnosticsActivity,
                    packageName + ".files", f)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, "Share report"))
            }
            val del = Button(this).apply { text = "Delete" }
            del.setOnClickListener { f.delete(); recreate() }
            row.addView(share); row.addView(del)
            root.addView(row)
            val body = TextView(this).apply { textSize = 12f; text = try { f.readText().take(1500) } catch (_: Exception) { "" } }
            root.addView(body)
        }
    }
}
