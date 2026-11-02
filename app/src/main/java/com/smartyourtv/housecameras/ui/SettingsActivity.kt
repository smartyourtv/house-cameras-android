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

        root.addView(TextView(this).apply {
            text = "Picture steadiness"; textSize = 17f
        })
        val steadyBox = CheckBox(this).apply {
            text = "Steady picture (smoother video on a shaky connection)"
            isChecked = cfg.steadyPicture
        }
        root.addView(steadyBox)
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "On: the app keeps a few extra seconds of video in reserve (about 10 seconds in the grid, " +
                   "about 4 in the big view), so a rough patch on the connection doesn't make the picture flash or freeze. " +
                   "The picture runs a few seconds behind real time. Off: the picture is as live as possible, " +
                   "but a shaky connection will show as stutter. Changing this re-opens the camera streams — no restart needed."
        })

        root.addView(TextView(this).apply { text = "On a TV box"; textSize = 17f })
        val bootBox = CheckBox(this).apply {
            text = "Open the cameras by themselves when the TV box turns on"
            isChecked = cfg.bootToCameras
        }
        root.addView(bootBox)
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "For a TV box in the garage or living room: when it powers up, it goes straight to the cameras. " +
                   "This does nothing on a phone or tablet."
        })
        val overlayState = TextView(this).apply { textSize = 13f }
        fun refreshOverlay() {
            val granted = android.provider.Settings.canDrawOverlays(this@SettingsActivity)
            overlayState.text = if (granted)
                "Opening over other apps: ALLOWED — the cameras can open by themselves when the box turns on."
            else
                "Opening over other apps: not allowed yet — without it, this box won't let the cameras open " +
                "by themselves at boot (the box starts the app but keeps it off the screen)."
        }
        refreshOverlay()
        root.addView(overlayState)
        val overlayBtn = Button(this).apply { text = "Allow opening over other apps" }
        overlayBtn.setOnClickListener {
            try {
                val i = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName"))
                startActivity(i)
            } catch (_: Exception) {
                Toast.makeText(this@SettingsActivity,
                    "Couldn't open that settings page on this box.", Toast.LENGTH_LONG).show()
            }
        }
        root.addView(overlayBtn)
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "After allowing it there, come back and reboot the box once to prove it. " +
                   "(This can also be granted from a computer by the household's tech helper.)"
        })

        root.addView(TextView(this).apply { text = "Van DVR feed (through the laptop)"; textSize = 17f })
        root.addView(TextView(this).apply {
            textSize = 13f
            text = "The van's DVR doesn't send its picture straight to this app — the laptop at home publishes it. " +
                   "If that link at home is off, DVR tiles honestly say the feed is down; they light up on their own when it returns."
        })
        val dvrHostF = field(cfg.dvr.host, "Laptop address for the DVR feed")
        val dvrPortF = field(cfg.dvr.port.toString(), "DVR feed port").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        root.addView(TextView(this).apply { text = "DVR feed address" }); root.addView(dvrHostF)
        root.addView(TextView(this).apply { text = "DVR feed port" }); root.addView(dvrPortF)

        val saveBtn = Button(this).apply { text = "Save" }
        saveBtn.setOnClickListener {
            cfg.bootToCameras = bootBox.isChecked
            cfg.dvr.host = dvrHostF.text.toString().trim().ifEmpty { cfg.dvr.host }
            cfg.dvr.port = dvrPortF.text.toString().toIntOrNull() ?: cfg.dvr.port
            cfg.steadyPicture = steadyBox.isChecked
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
