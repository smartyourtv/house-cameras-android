package com.smartyourtv.housecameras.ui

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import com.smartyourtv.housecameras.core.*
import kotlin.concurrent.thread

class EditCameraActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Store.load(this)
        val idx = intent.getIntExtra("index", -1)
        val isNew = idx < 0
        val cam = if (isNew) Camera(name = "camera-${cfg.cameras.size + 1}", family = "generic")
                  else cfg.cameras[idx]
        title = if (isNew) "Add a camera" else "Camera settings — ${cam.name}"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pad = (12 * resources.displayMetrics.density).toInt()
        root.setPadding(pad, pad, pad, pad)
        val scroll = ScrollView(this); scroll.addView(root); setContentView(scroll)

        fun label(t: String) = TextView(this).apply { text = t; textSize = 14f }
        fun field(value: String, hint: String) = EditText(this).apply { setText(value); this.hint = hint; textSize = 16f }
        val nameF = field(cam.name, "Name, like house-1")
        val hostF = field(cam.host, "Network address, like 192.168.40.25")
        val families = Templates.ALL.keys.toList()
        val familySpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@EditCameraActivity, android.R.layout.simple_spinner_dropdown_item,
                families.map { "${it} — ${Templates.forFamily(it).label}" })
            setSelection(families.indexOf(cam.family).coerceAtLeast(0))
        }
        val userF = field(cam.credentials.username, "Camera user name")
        val passF = field(cam.credentials.password, "Camera password").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val enabledBox = CheckBox(this).apply { text = "Show this camera"; isChecked = cam.enabled }
        val notesF = field(cam.notes, "Notes (where it is, what it watches)")
        val credNote = TextView(this).apply {
            textSize = 12f
            text = "The password is kept only on this phone, locked with the phone's secure key store. " +
                   "It is never sent anywhere except to the camera itself."
        }
        root.addView(label("Name")); root.addView(nameF)
        root.addView(label("Network address")); root.addView(hostF)
        root.addView(label("Camera family")); root.addView(familySpin)
        root.addView(label("User name")); root.addView(userF)
        root.addView(label("Password")); root.addView(passF); root.addView(credNote)
        root.addView(label("Notes")); root.addView(notesF); root.addView(enabledBox)

        val result = TextView(this).apply { textSize = 14f }
        val findBtn = Button(this).apply { text = "Find the picture automatically" }
        findBtn.setOnClickListener {
            readBack(cam, nameF, hostF, families, familySpin, userF, passF, notesF, enabledBox)
            result.text = "Trying each way of getting a picture, in order. This can take a little while…"
            thread(name = "ingest") {
                val winner = Ingest.resolve(cam)
                runOnUiThread {
                    if (winner != null) {
                        result.text = "Got a picture — using the ${winner.via} method (${winner.kind}). Saving will keep this."
                    } else {
                        result.text = "No picture could be found. This camera may not offer a picture on the home network — " +
                                      "cameras that only work through a company cloud app (Ring, Blink, stock Wyze, Nest) can't be added."
                    }
                }
            }
        }
        root.addView(findBtn); root.addView(result)

        val saveBtn = Button(this).apply { text = "Save" }
        saveBtn.setOnClickListener {
            readBack(cam, nameF, hostF, families, familySpin, userF, passF, notesF, enabledBox)
            if (cam.main.path.isEmpty()) {
                val tpl = Templates.forFamily(cam.family)
                if (tpl.main.path.isNotEmpty()) { cam.main = tpl.main.copy(); cam.sub = tpl.sub.copy() }
            }
            if (isNew) cfg.cameras.add(cam) else cfg.cameras[idx] = cam
            Store.save(this, cfg)
            Toast.makeText(this, "Saved.", Toast.LENGTH_SHORT).show(); finish()
        }
        root.addView(saveBtn)
        if (!isNew) {
            val del = Button(this).apply { text = "Remove this camera" }
            del.setOnClickListener { cfg.cameras.removeAt(idx); Store.save(this@EditCameraActivity, cfg); finish() }
            root.addView(del)
        }
        root.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun readBack(cam: Camera, nameF: EditText, hostF: EditText, families: List<String>,
                         spin: Spinner, userF: EditText, passF: EditText, notesF: EditText, enabled: CheckBox) {
        cam.name = nameF.text.toString().trim().ifEmpty { cam.name }
        cam.host = hostF.text.toString().trim()
        cam.family = families[spin.selectedItemPosition]
        cam.credentials.username = userF.text.toString().trim()
        cam.credentials.password = passF.text.toString()
        cam.notes = notesF.text.toString()
        cam.enabled = enabled.isChecked
    }
}
