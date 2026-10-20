package com.tapvpn.app

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object VpnState {
    private val active = AtomicBoolean(false)
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    fun isOn(): Boolean = active.get()

    fun set(on: Boolean) {
        if (active.getAndSet(on) == on) return
        listeners.forEach { it(on) }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
        listener(active.get())
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
