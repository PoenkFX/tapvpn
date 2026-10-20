package com.tapvpn.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class LocalVpnService : VpnService() {
    private val running = AtomicBoolean(false)
    private var tun: ParcelFileDescriptor? = null
    private var loop: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            enterForeground(NOTIF_ID, notification())
        } catch (error: Exception) {
            CrashLog.write(this, error)
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        if (running.get()) return
        try {
            openTunnel()
        } catch (error: Exception) {
            CrashLog.write(this, error)
            running.set(false)
            VpnState.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun openTunnel() {
        if (running.get()) return
        val established = establishBlocking()
        if (established == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        tun = established
        running.set(true)
        VpnState.set(true)
        try {
            enterForeground(NOTIF_ID, notification())
        } catch (_: Exception) {
        }
        loop = Thread({
            val input = FileInputStream(established.fileDescriptor)
            val packet = ByteArray(32767)
            try {
                while (running.get()) {
                    val n = input.read(packet)
                    if (n < 0) break
                }
            } catch (_: Exception) {
            }
        }, "tapvpn-tun").also { it.start() }
    }

    private fun establishBlocking(): ParcelFileDescriptor? {
        val withIp6 = try {
            blockingBuilder(ipv6 = true).establish()
        } catch (_: Exception) {
            null
        }
        if (withIp6 != null) return withIp6
        return try {
            blockingBuilder(ipv6 = false).establish()
        } catch (_: Exception) {
            null
        }
    }

    private fun blockingBuilder(ipv6: Boolean): Builder {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
            .setBlocking(true)
        if (ipv6) {
            builder.addAddress("fd00::1", 128)
            builder.addRoute("::", 0)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            builder.setMetered(false)
        }
        return builder
    }

    private fun stopTunnel() {
        running.set(false)
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        loop?.interrupt()
        loop = null
        VpnState.set(false)
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification() = NotificationCompat.Builder(this, TapVpnApp.CHANNEL_VPN)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.vpn_blocking))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        const val ACTION_START = "com.tapvpn.app.START_VPN"
        const val ACTION_STOP = "com.tapvpn.app.STOP_VPN"
        private const val NOTIF_ID = 41
        private const val TUN_ADDRESS = "10.8.0.2"

        fun start(context: Context) {
            val intent = Intent(context, LocalVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LocalVpnService::class.java).setAction(ACTION_STOP)
            context.startForegroundService(intent)
        }

        fun toggle(context: Context) {
            if (VpnState.isOn()) stop(context) else start(context)
        }
    }
}
