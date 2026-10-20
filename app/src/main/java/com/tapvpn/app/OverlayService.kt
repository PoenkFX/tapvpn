package com.tapvpn.app

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

class OverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var displayManager: DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var bubble: FloatingBubbleView? = null
    private var pinMenu: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var laidW = 0
    private var laidH = 0
    private var laidRot = -1

    private val stateListener: (Boolean) -> Unit = { on ->
        handler.post {
            if (!counting) bubble?.showState(on, null)
        }
    }
    private var counting = false
    private var remainTenths = 0
    private val tick = object : Runnable {
        override fun run() {
            if (!counting) return
            remainTenths -= 1
            if (remainTenths <= 0) {
                counting = false
                LocalVpnService.stop(this@OverlayService)
                bubble?.showState(false, null)
                return
            }
            bubble?.showState(true, formatTenths(remainTenths))
            handler.postDelayed(this, 100)
        }
    }

    private val watchRunnable = object : Runnable {
        override fun run() {
            try {
                if (bubble == null) return
                val box = screenBox()
                val detached = bubble?.isAttachedToWindow != true
                val changed = box.w != laidW || box.h != laidH || box.rot != laidRot
                if (detached || changed) {
                    keepOnScreen(readd = laidRot != -1 && box.rot != laidRot)
                }
            } catch (error: Exception) {
                CrashLog.write(this@OverlayService, error)
            }
            if (bubble != null) handler.postDelayed(this, 200)
        }
    }

    private var lastOrientBucket = -1
    private var orientationListener: OrientationEventListener? = null

    private val configCallback = object : ComponentCallbacks {
        override fun onConfigurationChanged(newConfig: Configuration) {
            handler.post { keepOnScreen(readd = true) }
        }

        override fun onLowMemory() = Unit
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) handler.post { keepOnScreen(readd = true) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        prefs = Prefs(this)
        registerComponentCallbacks(configCallback)
        displayManager.registerDisplayListener(displayListener, handler)
        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val bucket = ((orientation + 45) / 90) % 4
                if (bucket == lastOrientBucket) return
                lastOrientBucket = bucket
                handler.post { keepOnScreen(readd = true) }
                handler.postDelayed({ keepOnScreen(readd = true) }, 280)
                handler.postDelayed({ keepOnScreen(readd = false) }, 700)
            }
        }
        orientationListener = listener
        if (listener.canDetectOrientation()) listener.enable()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        keepOnScreen(readd = true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            enterForeground(NOTIF_ID, notification())
        } catch (error: Exception) {
            CrashLog.write(this, error)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            removeBubble()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        attachBubble()
        when (intent?.action) {
            ACTION_ARM -> armTimer()
            ACTION_DISARM -> {
                disarmTimer()
                bubble?.showState(VpnState.isOn(), null)
            }
        }
        handler.removeCallbacks(watchRunnable)
        handler.post(watchRunnable)
        return START_STICKY
    }

    private fun attachBubble() {
        if (bubble != null) {
            applySize()
            keepOnScreen(readd = true)
            return
        }
        val sizePx = dp(prefs.bubbleSizeDp)
        val box = screenBox()
        val layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                setFitInsetsIgnoringVisibility(true)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            val maxX = (box.w - sizePx).coerceAtLeast(0)
            val maxY = (box.h - sizePx).coerceAtLeast(0)
            x = (prefs.bubbleX * maxX).toInt().coerceIn(0, maxX)
            y = (prefs.bubbleY * maxY).toInt().coerceIn(0, maxY)
        }
        params = layoutParams
        laidW = box.w
        laidH = box.h
        laidRot = box.rot
        val view = FloatingBubbleView(this).apply {
            pinned = prefs.pinned
            showState(VpnState.isOn(), null)
            onToggle = { userToggle() }
            onLongPress = { togglePinMenu() }
            onMove = move@{ dx, dy ->
                hidePinMenu()
                val p = params ?: return@move
                val b = screenBox()
                val maxX = (b.w - p.width).coerceAtLeast(0)
                val maxY = (b.h - p.height).coerceAtLeast(0)
                p.x = (p.x + dx).coerceIn(0, maxX)
                p.y = (p.y + dy).coerceIn(0, maxY)
                updateOrReadd(this, p, false)
            }
            onMoveEnd = end@{
                val p = params ?: return@end
                val b = screenBox()
                val maxX = (b.w - p.width).coerceAtLeast(0)
                val maxY = (b.h - p.height).coerceAtLeast(0)
                prefs.bubbleX = if (maxX <= 0) 0f else (p.x.toFloat() / maxX).coerceIn(0f, 1f)
                prefs.bubbleY = if (maxY <= 0) 0f else (p.y.toFloat() / maxY).coerceIn(0f, 1f)
                laidW = b.w
                laidH = b.h
                laidRot = b.rot
            }
        }
        bubble = view
        windowManager.addView(view, layoutParams)
        VpnState.addListener(stateListener)
    }

    private fun userToggle() {
        if (counting || VpnState.isOn()) {
            disarmTimer()
            LocalVpnService.stop(this)
            bubble?.showState(false, null)
            return
        }
        LocalVpnService.start(this)
        if (prefs.timerEnabled) armTimer() else bubble?.showState(true, null)
    }

    private fun armTimer() {
        handler.removeCallbacks(tick)
        if (!prefs.timerEnabled) {
            counting = false
            bubble?.showState(true, null)
            return
        }
        remainTenths = prefs.timerTenths
        counting = true
        bubble?.showState(true, formatTenths(remainTenths))
        handler.postDelayed(tick, 100)
    }

    private fun disarmTimer() {
        counting = false
        handler.removeCallbacks(tick)
    }

    private fun formatTenths(tenths: Int): String {
        val safe = tenths.coerceAtLeast(0)
        return "${safe / 10}.${safe % 10}"
    }

    private fun togglePinMenu() {
        if (pinMenu != null) hidePinMenu() else showPinMenu()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun showPinMenu() {
        hidePinMenu()
        val view = TextView(this).apply {
            text = pinLabel()
            setTextColor(getColor(R.color.fg))
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundResource(R.drawable.bg_card)
            setOnClickListener {
                prefs.pinned = !prefs.pinned
                bubble?.pinned = prefs.pinned
                text = pinLabel()
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    hidePinMenu()
                    true
                } else {
                    false
                }
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
                setFitInsetsIgnoringVisibility(true)
            }
        }
        pinMenu = view
        try {
            windowManager.addView(view, lp)
        } catch (error: Exception) {
            CrashLog.write(this, error)
            pinMenu = null
            return
        }
        placePinMenu()
    }

    private fun pinLabel(): String {
        val name = getString(R.string.pin_menu)
        return if (prefs.pinned) "✓  $name" else name
    }

    private fun placePinMenu() {
        val menu = pinMenu ?: return
        val p = params ?: return
        val box = screenBox()
        val menuWidth = dp(148)
        val menuHeight = dp(44)
        val gap = dp(8)
        val lp = menu.layoutParams as? WindowManager.LayoutParams ?: return
        val x = if (p.x > box.w / 2) p.x - menuWidth - gap else p.x + p.width + gap
        val y = p.y + (p.height - menuHeight) / 2
        lp.x = x.coerceIn(0, (box.w - menuWidth).coerceAtLeast(0))
        lp.y = y.coerceIn(0, (box.h - menuHeight).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(menu, lp)
        } catch (_: Exception) {
        }
    }

    private fun hidePinMenu() {
        val menu = pinMenu ?: return
        pinMenu = null
        try {
            windowManager.removeView(menu)
        } catch (_: Exception) {
        }
    }

    private fun applySize() {
        val p = params ?: return
        p.width = dp(prefs.bubbleSizeDp)
        p.height = p.width
        keepOnScreen(readd = true)
    }

    private fun keepOnScreen(readd: Boolean) {
        val view = bubble ?: return
        val p = params ?: return
        val box = screenBox()
        val maxX = (box.w - p.width).coerceAtLeast(0)
        val maxY = (box.h - p.height).coerceAtLeast(0)
        val rotated = laidRot != -1 && box.rot != laidRot
        val resized = laidW > 0 && (box.w != laidW || box.h != laidH)
        if (rotated || resized || laidW == 0) {
            p.x = (prefs.bubbleX * maxX).toInt().coerceIn(0, maxX)
            p.y = (prefs.bubbleY * maxY).toInt().coerceIn(0, maxY)
        } else {
            p.x = p.x.coerceIn(0, maxX)
            p.y = p.y.coerceIn(0, maxY)
        }
        laidW = box.w
        laidH = box.h
        laidRot = box.rot
        updateOrReadd(view, p, readd || !view.isAttachedToWindow)
        placePinMenu()
    }

    private fun updateOrReadd(view: FloatingBubbleView, p: WindowManager.LayoutParams, readd: Boolean) {
        try {
            if (readd && view.isAttachedToWindow) {
                windowManager.removeView(view)
            }
            if (view.isAttachedToWindow) {
                windowManager.updateViewLayout(view, p)
            } else {
                windowManager.addView(view, p)
            }
        } catch (_: Exception) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }
            try {
                windowManager.addView(view, p)
            } catch (_: Exception) {
            }
        }
    }

    private data class ScreenBox(val w: Int, val h: Int, val rot: Int)

    @Suppress("DEPRECATION")
    private fun screenBox(): ScreenBox {
        try {
            return readScreen()
        } catch (error: Exception) {
            CrashLog.write(this, error)
            val dm = resources.displayMetrics
            val w = dm.widthPixels.coerceAtLeast(1)
            val h = dm.heightPixels.coerceAtLeast(1)
            return if (w >= h) ScreenBox(w, h, Surface.ROTATION_90) else ScreenBox(w, h, Surface.ROTATION_0)
        }
    }

    @Suppress("DEPRECATION")
    private fun readScreen(): ScreenBox {
        var w = 0
        var h = 0
        var rot = Surface.ROTATION_0
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            rot = display.rotation
            val point = Point()
            display.getRealSize(point)
            w = point.x
            h = point.y
        }
        if ((w <= 0 || h <= 0) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            w = bounds.width()
            h = bounds.height()
        }
        if (w <= 0 || h <= 0) {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            rot = windowManager.defaultDisplay.rotation
        }
        val longSide = maxOf(w, h)
        val shortSide = minOf(w, h)
        val landscape = when (lastOrientBucket) {
            1, 3 -> true
            0, 2 -> false
            else -> rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270 || w > h
        }
        return if (landscape) ScreenBox(longSide, shortSide, rot) else ScreenBox(shortSide, longSide, rot)
    }

    private fun removeBubble() {
        handler.removeCallbacks(watchRunnable)
        VpnState.removeListener(stateListener)
        disarmTimer()
        bubble?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
        params = null
        hidePinMenu()
        laidW = 0
        laidH = 0
        laidRot = -1
    }

    override fun onDestroy() {
        try {
            unregisterComponentCallbacks(configCallback)
        } catch (_: Exception) {
        }
        try {
            displayManager.unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        try {
            orientationListener?.disable()
        } catch (_: Exception) {
        }
        removeBubble()
        super.onDestroy()
    }

    private fun notification() = NotificationCompat.Builder(this, TapVpnApp.CHANNEL_OVERLAY)
        .setSmallIcon(R.drawable.ic_stat)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(getString(R.string.overlay_running))
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_START = "com.tapvpn.app.START_OVERLAY"
        const val ACTION_STOP = "com.tapvpn.app.STOP_OVERLAY"
        const val ACTION_RESIZE = "com.tapvpn.app.RESIZE_OVERLAY"
        const val ACTION_ARM = "com.tapvpn.app.ARM_TIMER"
        const val ACTION_DISARM = "com.tapvpn.app.DISARM_TIMER"
        private const val NOTIF_ID = 42

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            context.startForegroundService(
                Intent(context, OverlayService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }

        fun resize(context: Context) {
            context.startForegroundService(
                Intent(context, OverlayService::class.java).setAction(ACTION_RESIZE),
            )
        }

        fun arm(context: Context) {
            if (!Settings.canDrawOverlays(context) || !Prefs(context).overlayOn) return
            context.startForegroundService(
                Intent(context, OverlayService::class.java).setAction(ACTION_ARM),
            )
        }

        fun disarm(context: Context) {
            if (!Settings.canDrawOverlays(context) || !Prefs(context).overlayOn) return
            context.startForegroundService(
                Intent(context, OverlayService::class.java).setAction(ACTION_DISARM),
            )
        }
    }
}
