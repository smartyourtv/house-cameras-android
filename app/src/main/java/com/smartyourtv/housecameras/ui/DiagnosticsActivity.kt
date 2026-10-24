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
                for (c in cfg.cameras) {
                    appendLine("• ${c.name} — ${c.host.ifEmpty { "no address" }} — ${Templates.forFamily(c.family).label}" +
                        " — picture by: ${c.main.via.ifEmpty { "not found yet" }}")
                }
                if (Store.repairLog.isNotEmpty())
                    appendLine("Self-repairs: ${Store.repairLog.joinToString("; ")}")
            }
        })

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
