package com.autobile.runtime.perception

import android.content.Context
import android.graphics.Bitmap
import com.autobile.core.common.Logx
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.ScreenSnapshot
import com.autobile.runtime.accessibility.AccessibilityBridge
import com.autobile.runtime.accessibility.ScreenshotOutcome
import kotlinx.coroutines.delay

/**
 * Observes the screen for the rest of the runtime.
 *
 * The accessibility tree is always read first because it is structured, cheap and needs
 * no image processing. A screenshot is taken only when a caller explicitly needs pixels,
 * which keeps the expensive and privacy-sensitive path off the common case.
 */
class PerceptionEngine(
    private val context: Context,
    private val treeReader: UiTreeReader = UiTreeReader(),
) {

    /**
     * Reads the current screen.
     *
     * @param settleMs time to let the UI finish animating first. Reading mid-transition
     *   produces a snapshot of a screen that no longer exists a moment later.
     */
    suspend fun observe(settleMs: Long = DEFAULT_SETTLE_MS): PerceptionResult {
        val service = AccessibilityBridge.require()
            ?: return PerceptionResult.Unavailable("Accessibility access is not granted")

        if (settleMs > 0) delay(settleMs)

        val root = service.activeRoot()
            ?: return PerceptionResult.Unavailable("No inspectable window is in the foreground")

        val metrics = context.resources.displayMetrics
        val snapshot = treeReader.read(
            root = root,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            windowTitle = runCatching { root.paneTitle?.toString() }.getOrNull().orEmpty(),
        )
        return PerceptionResult.Success(snapshot)
    }

    /**
     * Reads the screen and waits until it stops changing, or until [timeoutMs] elapses.
     *
     * Used after an action, where the useful moment is when the transition has finished
     * rather than a fixed delay that is either too short or wasteful.
     */
    suspend fun observeStable(
        timeoutMs: Long = DEFAULT_STABILITY_TIMEOUT_MS,
        settleMs: Long = DEFAULT_SETTLE_MS,
    ): PerceptionResult {
        val deadline = System.currentTimeMillis() + timeoutMs
        var previous: ScreenSnapshot? = null

        while (System.currentTimeMillis() < deadline) {
            when (val result = observe(settleMs)) {
                is PerceptionResult.Success -> {
                    val current = result.snapshot
                    if (previous != null && current.isEquivalentTo(previous)) return result
                    previous = current
                }

                else -> return result
            }
        }
        return previous?.let { PerceptionResult.Success(it) }
            ?: PerceptionResult.Unavailable("Screen did not settle")
    }

    /**
     * Captures pixels.
     *
     * Separate from [observe] so that taking a screenshot is always a deliberate act by
     * the caller rather than something that happens implicitly on every observation.
     */
    suspend fun captureScreenshot(): ScreenshotCapture {
        val service = AccessibilityBridge.require()
            ?: return ScreenshotCapture.Unavailable("Accessibility access is not granted")

        repeat(SCREENSHOT_ATTEMPTS) { attempt ->
            when (val outcome = service.captureScreen()) {
                is ScreenshotOutcome.Captured -> return ScreenshotCapture.Success(outcome.bitmap)
                ScreenshotOutcome.SecureWindowBlocked ->
                    return ScreenshotCapture.SecureWindowBlocked(service.foregroundPackage())

                ScreenshotOutcome.Throttled -> delay(THROTTLE_BACKOFF_MS * (attempt + 1))
                is ScreenshotOutcome.Failed -> {
                    Logx.w("Screenshot failed: ${outcome.reason}")
                    return ScreenshotCapture.Unavailable(outcome.reason)
                }
            }
        }
        return ScreenshotCapture.Unavailable("Screenshots are rate limited right now")
    }

    private companion object {
        const val DEFAULT_SETTLE_MS = 250L
        const val DEFAULT_STABILITY_TIMEOUT_MS = 4_000L
        const val SCREENSHOT_ATTEMPTS = 3
        const val THROTTLE_BACKOFF_MS = 400L
    }
}

sealed interface ScreenshotCapture {
    data class Success(val bitmap: Bitmap) : ScreenshotCapture
    data class SecureWindowBlocked(val packageName: String) : ScreenshotCapture
    data class Unavailable(val reason: String) : ScreenshotCapture
}

/**
 * Compares two snapshots for practical equivalence.
 *
 * Bounds are ignored because a list can scroll by a pixel while showing the same thing;
 * what matters is whether the same elements are present in the same window.
 */
fun ScreenSnapshot.isEquivalentTo(other: ScreenSnapshot): Boolean {
    if (packageName != other.packageName) return false
    if (windowTitle != other.windowTitle) return false
    if (nodes.size != other.nodes.size) return false
    return allText() == other.allText()
}
