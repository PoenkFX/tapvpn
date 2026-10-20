package com.tapvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        try {
            if (prefs.overlayOn && Settings.canDrawOverlays(context)) {
                OverlayService.start(context)
            }
        } catch (error: Exception) {
            CrashLog.write(context, error)
        }
    }
}
