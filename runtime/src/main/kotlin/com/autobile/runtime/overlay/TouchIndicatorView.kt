package com.autobile.runtime.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.autobile.core.model.Bounds

/**
 * Draws a pulsing ring around the element the agent is acting on.
 *
 * A ring rather than a filled shape, so the user can still read the control underneath
 * and judge for themselves whether the agent picked the right one.
 */
class TouchIndicatorView(context: Context) : View(context) {

    private var target: RectF? = null
    private var pulse = 0f
    private var animator: ValueAnimator? = null

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_WIDTH_PX
        color = ACCENT_COLOR
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RING_WIDTH_PX / 2
        color = ACCENT_COLOR
    }

    fun highlight(bounds: Bounds) {
        target = RectF(
            bounds.left.toFloat(),
            bounds.top.toFloat(),
            bounds.right.toFloat(),
            bounds.bottom.toFloat(),
        )
        startPulse()
        invalidate()
    }

    fun clear() {
        animator?.cancel()
        animator = null
        target = null
        invalidate()
    }

    private fun startPulse() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = target ?: return

        val expansion = PULSE_EXPANSION_PX * pulse
        glowPaint.alpha = ((1f - pulse) * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA)
        canvas.drawRoundRect(
            RectF(
                rect.left - expansion,
                rect.top - expansion,
                rect.right + expansion,
                rect.bottom + expansion,
            ),
            CORNER_RADIUS_PX,
            CORNER_RADIUS_PX,
            glowPaint,
        )

        ringPaint.alpha = MAX_ALPHA
        canvas.drawRoundRect(rect, CORNER_RADIUS_PX, CORNER_RADIUS_PX, ringPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val PULSE_DURATION_MS = 900L
        const val RING_WIDTH_PX = 6f
        const val CORNER_RADIUS_PX = 16f
        const val PULSE_EXPANSION_PX = 22f
        const val MAX_ALPHA = 255
        val ACCENT_COLOR = Color.parseColor("#4F7CFF")
    }
}
