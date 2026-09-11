package com.autobile.runtime.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.autobile.core.common.Logx
import com.autobile.core.model.Bounds

/**
 * Shows what the agent is doing, on top of whatever app it is operating.
 *
 * This is not decoration. A phone that starts navigating by itself is alarming unless
 * the user can see what it is doing and stop it, and during development the indicator is
 * frequently the only way to tell a mis-resolved target from a mis-timed one.
 *
 * The overlay is entirely optional: without the permission the agent runs normally and
 * only the visibility is lost, so the feature is never a prerequisite for automation.
 */
class AgentOverlayController(private val context: Context) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var bannerView: View? = null
    private var indicatorView: TouchIndicatorView? = null

    val isPermitted: Boolean get() = Settings.canDrawOverlays(context)

    /**
     * Shows the status banner.
     *
     * Attached at the top of the screen and explicitly not focusable or touchable, so it
     * cannot intercept input intended for the app underneath — including input the agent
     * itself is about to deliver.
     */
    fun showBanner(view: View) {
        if (!isPermitted) return
        hideBanner()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        runCatching { windowManager?.addView(view, params) }
            .onSuccess { bannerView = view }
            .onFailure { Logx.w("Could not show the agent banner", it) }
    }

    fun hideBanner() {
        bannerView?.let { view ->
            runCatching { windowManager?.removeView(view) }
            bannerView = null
        }
    }

    /** Marks the element the agent is about to act on. */
    fun showTouchIndicator(bounds: Bounds) {
        if (!isPermitted || bounds.isEmpty) return
        val view = indicatorView ?: TouchIndicatorView(context).also { created ->
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            )
            runCatching { windowManager?.addView(created, params) }
                .onSuccess { indicatorView = created }
                .onFailure { Logx.w("Could not show the touch indicator", it) }
        }
        view.highlight(bounds)
    }

    fun hideTouchIndicator() {
        indicatorView?.let { view ->
            view.clear()
            runCatching { windowManager?.removeView(view) }
            indicatorView = null
        }
    }

    fun dismissAll() {
        hideTouchIndicator()
        hideBanner()
    }

    /**
     * The window type used for both overlays.
     *
     * This is the only type an ordinary application may use to draw above other apps,
     * and it is gated behind the display-over-apps permission checked in [isPermitted].
     */
    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
}
