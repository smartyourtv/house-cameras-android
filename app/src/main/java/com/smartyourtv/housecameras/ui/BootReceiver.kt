package com.smartyourtv.housecameras.ui

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import com.smartyourtv.housecameras.core.Store

/** Boot-straight-to-cameras (owner ruling 2026-11-02: receiver
 *  first, home-app only if a box refuses). Fires only when the
 *  setting is on AND the device is a television — phones never
 *  boot-launch. Android-version background-start limits vary by
 *  vendor build; the W2's behavior is a P3 field check. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") return
        try {
            val cfg = Store.load(ctx)
            if (!cfg.bootToCameras) return
            val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
            if (ui?.currentModeType != Configuration.UI_MODE_TYPE_TELEVISION) return
            val launch = Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(launch)
        } catch (_: Exception) { /* a box that refuses is a P3 finding, not a crash */ }
    }
}

fun isTelevision(ctx: Context): Boolean = try {
    val ui = ctx.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    ui?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
} catch (_: Exception) { false }
