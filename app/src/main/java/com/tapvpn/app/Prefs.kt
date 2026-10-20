package com.tapvpn.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tapvpn", Context.MODE_PRIVATE)

    var overlayOn: Boolean
        get() = sp.getBoolean(KEY_OVERLAY, true)
        set(value) = sp.edit().putBoolean(KEY_OVERLAY, value).apply()

    var bubbleSizeDp: Int
        get() = sp.getInt(KEY_SIZE, DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
        set(value) = sp.edit().putInt(KEY_SIZE, value.coerceIn(MIN_SIZE, MAX_SIZE)).apply()

    var bubbleX: Float
        get() = sp.getFloat(KEY_X, 1f)
        set(value) = sp.edit().putFloat(KEY_X, value).apply()

    var bubbleY: Float
        get() = sp.getFloat(KEY_Y, 0.62f)
        set(value) = sp.edit().putFloat(KEY_Y, value).apply()

    var pinned: Boolean
        get() = sp.getBoolean(KEY_PIN, false)
        set(value) = sp.edit().putBoolean(KEY_PIN, value).apply()

    var timerEnabled: Boolean
        get() = sp.getBoolean(KEY_TIMER, false)
        set(value) = sp.edit().putBoolean(KEY_TIMER, value).apply()

    var timerTenths: Int
        get() = sp.getInt(KEY_TIMER_TENTHS, 30).coerceIn(1, 100)
        set(value) = sp.edit().putInt(KEY_TIMER_TENTHS, value.coerceIn(1, 100)).apply()

    fun targetPackages(): Set<String> = sp.getStringSet(KEY_TARGETS, emptySet())?.toSet() ?: emptySet()

    fun setTargetPackages(packages: Set<String>) {
        sp.edit().putStringSet(KEY_TARGETS, HashSet(packages)).apply()
    }

    fun toggleTarget(packageName: String): Boolean {
        val next = targetPackages().toMutableSet()
        val added = if (next.contains(packageName)) {
            next.remove(packageName)
            false
        } else {
            next.add(packageName)
            true
        }
        setTargetPackages(next)
        return added
    }

    companion object {
        const val MIN_SIZE = 48
        const val MAX_SIZE = 96
        const val DEFAULT_SIZE = 64
        private const val KEY_OVERLAY = "overlay_on"
        private const val KEY_SIZE = "bubble_size"
        private const val KEY_X = "bubble_x"
        private const val KEY_Y = "bubble_y"
        private const val KEY_PIN = "bubble_pinned"
        private const val KEY_TIMER = "timer_on"
        private const val KEY_TIMER_TENTHS = "timer_tenths"
        private const val KEY_TARGETS = "target_packages"
    }
}
