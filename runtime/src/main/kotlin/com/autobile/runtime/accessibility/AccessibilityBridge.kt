package com.autobile.runtime.accessibility

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import com.autobile.core.common.Logx
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Connection point between the accessibility service and the rest of the runtime.
 *
 * An accessibility service is created by the system, not by the application, so there is
 * no constructor to inject through. This object holds the live instance and exposes
 * connection state as a flow, which lets the UI react to the user revoking access
 * mid-session instead of discovering it at the next failed step.
 */
object AccessibilityBridge {

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /**
     * Raw accessibility events.
     *
     * Replay is zero and the buffer is small on purpose: these arrive at high frequency
     * during normal phone use and only matter to whoever is listening at the time.
     */
    private val _events = MutableSharedFlow<ObservedEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<ObservedEvent> = _events.asSharedFlow()

    @Volatile
    private var service: AutobileAccessibilityService? = null

    internal fun attach(instance: AutobileAccessibilityService) {
        service = instance
        _connected.value = true
    }

    internal fun detach() {
        service = null
        _connected.value = false
        Logx.i("Accessibility service disconnected")
    }

    internal fun publish(event: AccessibilityEvent) {
        val observed = ObservedEvent(
            type = event.eventType,
            packageName = event.packageName?.toString().orEmpty(),
            className = event.className?.toString().orEmpty(),
            text = event.text.joinToString(" ") { it.toString() },
            contentDescription = event.contentDescription?.toString(),
            timestamp = System.currentTimeMillis(),
        )
        _events.tryEmit(observed)
    }

    /** The connected service, or null when the user has not granted access. */
    fun require(): AutobileAccessibilityService? = service

    /**
     * Whether the user has enabled Autobile in accessibility settings.
     *
     * Read from settings rather than from [connected] because the service may be enabled
     * but not yet bound when this is called during startup.
     */
    fun isEnabledInSettings(context: Context): Boolean {
        val expected = "${context.packageName}/${AutobileAccessibilityService::class.java.name}"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (enabled.isEmpty()) return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        return splitter.any { it.equals(expected, ignoreCase = true) }
    }
}

/** An accessibility event reduced to the fields the runtime uses. */
data class ObservedEvent(
    val type: Int,
    val packageName: String,
    val className: String,
    val text: String,
    val contentDescription: String?,
    val timestamp: Long,
) {
    val isWindowChange: Boolean
        get() = type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    val isInteraction: Boolean
        get() = type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SELECTED
}
