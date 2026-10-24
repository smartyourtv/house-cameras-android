package com.smartyourtv.housecameras.ui

import android.app.Activity
import android.os.Bundle
import android.widget.*
import com.smartyourtv.housecameras.BuildConfig
import com.smartyourtv.housecameras.core.*
import kotlin.concurrent.thread

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Settings"
        val cfg = Store.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (12 * resources.displayMetrics.density).toInt(); root.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)

        root.addView(TextView(this).apply {
            text = "Where you are watching from"; textSize = 17f
        })
        val awayBox = CheckBox(this).apply {
            text = "I am away from home (watch through the home relay)"
            isChecked = cfg.relay.enabled
        }
        root.addView(awayBox)
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "At home, the app talks straight to the cameras. Away, it never talks to the cameras at all — " +
                   "it talks only to the small relay running at home (on the ThinkPad, at its Tailscale address 100.78.8.93). " +
                   "The relay is normally OFF — that is its resting state, not a fault. A viewing session is started at home " +
                   "(ask Scout), and each session has its own fresh code: the user name is always \"scout\" and the code is the password. " +
                   "A session closes itself after about 10 minutes with nobody watching, and never runs longer than 2 hours — " +
                   "when it closes, you need a new session and a new code. Remote viewing carries house-1 to house-6 (main picture)."
        })
        fun field(v: String, hint: String) = EditText(this).apply { setText(v); this.hint = hint; textSize = 16f }
        val hostF = field(cfg.relay.host, "Relay address (Tailscale name or 100.x address)")
        val portF = field(cfg.relay.port.toString(), "Relay video port").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val tokenF = field(cfg.relay.sessionToken, "Session code for this session (user name is always scout)").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
        root.addView(TextView(this).apply { text = "Relay address" }); root.addView(hostF)
        root.addView(TextView(this).apply { text = "Relay video port" }); root.addView(portF)
        root.addView(TextView(this).apply { text = "Session code" }); root.addView(tokenF)
        val relayState = TextView(this).apply {
            textSize = 13f
            text = if (cfg.relay.sessionToken.isEmpty()) "Relay status: no session code entered. The relay is stopped until a session " +
                   "is started at home — Away mode will show \"not connected\" until then, which is normal."
                   else "Relay status: a session code is entered. If the session has ended, cameras will show \"not connected\" — " +
                        "start a new session at home to get a fresh code."
        }
        root.addView(relayState)

        val saveBtn = Button(this).apply { text = "Save" }
        saveBtn.setOnClickListener {
            cfg.relay.enabled = awayBox.isChecked
            cfg.relay.host = hostF.text.toString().trim()
            cfg.relay.port = portF.text.toString().toIntOrNull() ?: 8554
            cfg.relay.sessionToken = tokenF.text.toString()
            Store.save(this, cfg)
            Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show(); finish()
        }
        root.addView(saveBtn)

        root.addView(TextView(this).apply { text = "Updates"; textSize = 17f })
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "This is version ${BuildConfig.VERSION_NAME}. Updates are never forced and never install by themselves — " +
                   "you tap the button, the download is checked for safety, and then the phone asks you to confirm the install."
        })
        val updResult = TextView(this).apply { textSize = 14f }
        val updBtn = Button(this).apply { text = "Check for an update" }
        updBtn.setOnClickListener {
            updResult.text = "Checking…"
            thread(name = "updater-manual") {
                val avail = Updater.check(BuildConfig.VERSION_CODE)
                runOnUiThread {
                    if (avail == null) updResult.text = "You have the newest version."
                    else {
                        updResult.text = "Version ${avail.versionName} is available. Tap below to download it."
                        val dl = Button(this@SettingsActivity).apply { text = "Download and install ${avail.versionName}" }
                        dl.setOnClickListener {
                            thread(name = "updater-dl") {
                                val msg = Updater.downloadAndInstall(this@SettingsActivity, avail.sha256)
                                runOnUiThread { updResult.text = msg }
                            }
                        }
                        root.addView(dl)
                    }
                }
            }
        }
        root.addView(updBtn); root.addView(updResult)
    }
}
