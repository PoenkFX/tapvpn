package com.tapvpn.app

import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.os.Build

fun Service.enterForeground(id: Int, notification: Notification) {
    if (Build.VERSION.SDK_INT >= 34) {
        startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    } else {
        startForeground(id, notification)
    }
}
