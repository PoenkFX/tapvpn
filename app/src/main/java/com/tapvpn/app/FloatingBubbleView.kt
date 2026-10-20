package com.tapvpn.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import kotlin.math.abs

@SuppressLint("ClickableViewAccessibility")
class FloatingBubbleView(context: Context) : FrameLayout(context) {
    var onToggle: (() -> Unit)? = null
    var onMove: ((dx: Int, dy: Int) -> Unit)? = null
    var onMoveEnd: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var pinned: Boolean = false

    private val label = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var longPressed = false
    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressed = true
            onLongPress?.invoke()
        }
    }

    init {
        isClickable = true
        isFocusable = false
        isFocusableInTouchMode = false
        isHapticFeedbackEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clipChildren = false
        clipToPadding = false
        label.gravity = Gravity.CENTER
        label.setTypeface(label.typeface, android.graphics.Typeface.BOLD)
        label.includeFontPadding = false
        label.isClickable = false
        label.isFocusable = false
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        showState(false, null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, w * 0.30f)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        super.dispatchTouchEvent(event)
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastX = event.rawX
                lastY = event.rawY
                dragging = false
                longPressed = false
                parent?.requestDisallowInterceptTouchEvent(true)
                handler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (!dragging && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) {
                    dragging = true
                    handler.removeCallbacks(longPressRunnable)
                }
                if (dragging && !pinned && !longPressed) {
                    onMove?.invoke((event.rawX - lastX).toInt(), (event.rawY - lastY).toInt())
                    lastX = event.rawX
                    lastY = event.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPressRunnable)
                when {
                    longPressed -> Unit
                    dragging && !pinned -> onMoveEnd?.invoke()
                    !dragging -> onToggle?.invoke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPressRunnable)
                if (dragging && !pinned) onMoveEnd?.invoke()
            }
        }
        return true
    }

    fun showState(on: Boolean, countdown: String?) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                ContextCompat.getColor(
                    context,
                    if (on || countdown != null) R.color.bubble_on else R.color.bubble_off,
                ),
            )
            alpha = 176
        }
        background = bg
        label.text = countdown ?: if (on) "ON" else "OFF"
        label.setTextColor(
            ContextCompat.getColor(
                context,
                if (on || countdown != null) R.color.bubble_on_fg else R.color.bubble_off_fg,
            ),
        )
        contentDescription = countdown ?: context.getString(if (on) R.string.vpn_on else R.string.vpn_off)
    }
}
