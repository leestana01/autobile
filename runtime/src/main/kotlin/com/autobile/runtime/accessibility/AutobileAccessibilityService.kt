package com.autobile.runtime.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.autobile.core.common.Logx
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * The single point of contact between Autobile and the Android accessibility system.
 *
 * Everything the agent can perceive or do on screen passes through this service: the
 * node tree, screenshots, gestures and global navigation. Keeping that surface in one
 * class means the rest of the runtime never touches framework node objects directly and
 * can be exercised without a device.
 *
 * The service publishes itself to [AccessibilityBridge] on connect and withdraws on
 * disconnect, so callers always observe the live connection state instead of assuming
 * the user has granted access.
 */
class AutobileAccessibilityService : AccessibilityService() {

    private val screenshotExecutor = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SELECTED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = EVENT_THROTTLE_MS
        }
        AccessibilityBridge.attach(this)
        Logx.i("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        AccessibilityBridge.publish(event)
    }

    override fun onInterrupt() {
        Logx.w("Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AccessibilityBridge.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityBridge.detach()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    /** The root of the foreground window, or null when no window is inspectable. */
    fun activeRoot(): AccessibilityNodeInfo? = runCatching { rootInActiveWindow }.getOrNull()

    /** Package name of the foreground window, or an empty string when unknown. */
    fun foregroundPackage(): String = runCatching {
        rootInActiveWindow?.packageName?.toString().orEmpty()
    }.getOrDefault("")

    /**
     * Captures the current screen.
     *
     * Returns [ScreenshotOutcome.SecureWindowBlocked] when the window contains protected
     * content. That refusal is respected as a final answer: a step that cannot see the
     * screen reports so rather than attempting to work around the protection.
     */
    suspend fun captureScreen(timeoutMs: Long = SCREENSHOT_TIMEOUT_MS): ScreenshotOutcome {
        val deferred = CompletableDeferred<ScreenshotOutcome>()
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                screenshotExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val bitmap = runCatching {
                            Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        }.getOrNull()
                        runCatching { result.hardwareBuffer.close() }
                        deferred.complete(
                            if (bitmap == null) {
                                ScreenshotOutcome.Failed("Screenshot buffer could not be decoded")
                            } else {
                                ScreenshotOutcome.Captured(bitmap)
                            },
                        )
                    }

                    override fun onFailure(errorCode: Int) {
                        deferred.complete(
                            when (errorCode) {
                                ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                                    ScreenshotOutcome.Failed("Display is not available")

                                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                    ScreenshotOutcome.Throttled

                                else -> ScreenshotOutcome.SecureWindowBlocked
                            },
                        )
                    }
                },
            )
        } catch (e: Throwable) {
            Logx.w("Screenshot request rejected", e)
            return ScreenshotOutcome.Failed(e.message ?: "screenshot unavailable")
        }
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: ScreenshotOutcome.Failed("Screenshot timed out")
    }

    /**
     * Dispatches a gesture and waits for the system to report completion.
     *
     * Waiting matters: the next step's perception must observe the screen after this
     * gesture has been delivered, not while it is still in flight.
     */
    suspend fun dispatchGestureAwait(gesture: GestureDescription, timeoutMs: Long = GESTURE_TIMEOUT_MS): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        val accepted = runCatching {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        deferred.complete(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        deferred.complete(false)
                    }
                },
                null,
            )
        }.getOrDefault(false)
        if (!accepted) return false
        return withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
    }

    /**
     * The capabilities the user actually granted.
     *
     * Declaring a capability in the service configuration is a request, not a
     * guarantee: the platform decides what to grant, and some builds withhold screen
     * capture. Reading the granted bits keeps the capability profile a probe rather
     * than an assumption.
     */
    fun grantedCapabilities(): GrantedCapabilities {
        val capabilities = runCatching { serviceInfo?.capabilities ?: 0 }.getOrDefault(0)
        return GrantedCapabilities(
            canTakeScreenshot = capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0,
            canPerformGestures = capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0,
            canRetrieveWindowContent =
                capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0,
        )
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    companion object {
        private const val EVENT_THROTTLE_MS = 50L
        private const val SCREENSHOT_TIMEOUT_MS = 4_000L
        private const val GESTURE_TIMEOUT_MS = 8_000L
    }
}

/** What the platform actually granted this service, as opposed to what it asked for. */
data class GrantedCapabilities(
    val canTakeScreenshot: Boolean,
    val canPerformGestures: Boolean,
    val canRetrieveWindowContent: Boolean,
)

/** Result of a screenshot attempt, including the reasons it may legitimately fail. */
sealed interface ScreenshotOutcome {
    data class Captured(val bitmap: Bitmap) : ScreenshotOutcome

    /** The window contains protected content. Not recoverable, and not worked around. */
    data object SecureWindowBlocked : ScreenshotOutcome

    /** The platform rate-limits screenshots; the caller should back off and retry. */
    data object Throttled : ScreenshotOutcome

    data class Failed(val reason: String) : ScreenshotOutcome
}
