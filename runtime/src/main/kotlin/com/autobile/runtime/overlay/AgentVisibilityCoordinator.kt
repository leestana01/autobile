package com.autobile.runtime.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.TextView
import com.autobile.core.data.SettingsStore
import com.autobile.runtime.agent.AgentActivity
import com.autobile.runtime.agent.AgentOrchestrator
import com.autobile.runtime.background.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps every user-visible execution surface in sync with the orchestrator.
 *
 * The notification keeps Android from reclaiming an active run and provides a stop
 * action. The overlay explains what is happening above the app being operated, while
 * the touch marker shows which control will be used next. All three are driven from
 * the same state so they cannot disagree about whether an automation is active.
 */
class AgentVisibilityCoordinator(
    private val context: Context,
    private val orchestrator: AgentOrchestrator,
    private val settings: SettingsStore,
    private val overlay: AgentOverlayController = AgentOverlayController(context),
) {
    private var collection: Job? = null

    fun start(scope: CoroutineScope) {
        if (collection != null) return
        collection = scope.launch {
            orchestrator.activity.collectLatest { activity ->
                when (activity) {
                    AgentActivity.Idle -> {
                        overlay.dismissAll()
                        AgentForegroundService.stop(context)
                    }

                    is AgentActivity.Running -> {
                        AgentForegroundService.start(context)
                        overlay.showBanner(activity.bannerView())
                        if (settings.showTouchIndicator) {
                            activity.touchTarget?.let(overlay::showTouchIndicator)
                                ?: overlay.hideTouchIndicator()
                        } else {
                            overlay.hideTouchIndicator()
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        collection?.cancel()
        collection = null
        overlay.dismissAll()
        AgentForegroundService.stop(context)
    }

    private fun AgentActivity.Running.bannerView(): TextView = TextView(context).apply {
        text = buildString {
            append("Autobile is working\n")
            append(skillName)
            append(" · ")
            append(stepDescription)
            append(" (")
            append(stepIndex + 1)
            append('/')
            append(totalSteps)
            append(')')
            repairNote?.let { append("\nAdapting: ").append(it) }
        }
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.argb(235, 28, 32, 38))
        setPadding(dp(20), dp(12), dp(20), dp(12))
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        contentDescription = text
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
