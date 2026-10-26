package com.m57.hermescontrol.ui.chat

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

internal object IntelligentUiSizing {
    const val MIN_HEIGHT = 96
    const val INITIAL_HEIGHT = 240
    const val INLINE_LIMIT = 1200
    const val EXPANDED_LIMIT = 2000

    /** A fixed host expression yields a JSON number, never generated code or arbitrary messages. */
    fun reportedHeight(value: String?): Int? =
        value
            ?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0 && it <= 10_000 }
            ?.let { ceil(it).toInt() }
}

/** Pulls one bounded display measurement; no JavaScript object with Android privileges is exposed. */
internal class IntelligentPreviewWebView(
    context: Context,
) : WebView(context) {
    var onHeight: (Int) -> Unit = {}
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var directionDecided = false
    private var horizontalGesture = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                directionDecided = false
                horizontalGesture = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - touchStartX)
                val dy = abs(event.y - touchStartY)
                if (!directionDecided && max(dx, dy) > touchSlop) {
                    directionDecided = true
                    horizontalGesture = dx > dy
                }
                if (horizontalGesture) parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        val handled = super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                directionDecided = false
                horizontalGesture = false
            }

            MotionEvent.ACTION_MOVE -> {
                // AndroidView dispatches unclaimed moves before Compose's Main pass. Claim only
                // horizontal preview drags here, before history can cancel the WebView's stream.
                if (handled && horizontalGesture) parent?.requestDisallowInterceptTouchEvent(true)
            }
        }
        return handled
    }

    private var measuring = false
    private val measure =
        object : Runnable {
            override fun run() {
                if (!measuring || !isAttachedToWindow) return
                if (isShown) {
                    val expectedDocument = tag
                    evaluateJavascript("window.__hermesPreviewHeight || 0") { value ->
                        if (measuring && tag === expectedDocument) {
                            IntelligentUiSizing.reportedHeight(value)?.let(onHeight)
                        }
                    }
                }
                if (measuring) postDelayed(this, 500)
            }
        }

    private fun startMeasuring() {
        if (measuring || !isAttachedToWindow || windowVisibility != View.VISIBLE) return
        measuring = true
        postDelayed(measure, 120)
    }

    fun stopMeasuring() {
        measuring = false
        removeCallbacks(measure)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startMeasuring()
    }

    override fun onDetachedFromWindow() {
        parent?.requestDisallowInterceptTouchEvent(false)
        stopMeasuring()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) startMeasuring() else stopMeasuring()
    }
}
