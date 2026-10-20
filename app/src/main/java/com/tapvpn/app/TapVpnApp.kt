package com.tapvpn.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class TapVpnApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_VPN,
                getString(R.string.channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_OVERLAY,
                getString(R.string.channel_overlay),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_VPN = "tapvpn.vpn"
        const val CHANNEL_OVERLAY = "tapvpn.overlay"
    }
}
