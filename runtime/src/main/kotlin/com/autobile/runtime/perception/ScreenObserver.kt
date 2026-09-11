package com.autobile.runtime.perception

import com.autobile.core.model.PerceptionResult

/**
 * Reads the screen on behalf of the runtime.
 *
 * Separating this from [PerceptionEngine] keeps the accessibility framework out of the
 * executor's type signature, which is what allows the execution loop — the part of the
 * system where a mistake actually presses buttons on someone's phone — to be tested
 * without a device or an emulator.
 *
 * Default timings live here so every caller shares them and a change applies everywhere.
 */
interface ScreenObserver {

    /**
     * Reads the current screen.
     *
     * @param settleMs time to let the interface finish animating first. Reading
     *   mid-transition describes a screen that no longer exists a moment later.
     */
    suspend fun observe(settleMs: Long = DEFAULT_SETTLE_MS): PerceptionResult

    /**
     * Reads the screen and waits until it stops changing.
     *
     * Used after an action, where the useful moment is when the transition has finished
     * rather than a fixed delay that is either too short or wasteful.
     */
    suspend fun observeStable(
        timeoutMs: Long = DEFAULT_STABILITY_TIMEOUT_MS,
        settleMs: Long = DEFAULT_SETTLE_MS,
    ): PerceptionResult

    /**
     * Captures pixels.
     *
     * Separate from [observe] so taking a screenshot is always a deliberate act rather
     * than something that happens implicitly on every observation.
     */
    suspend fun captureScreenshot(): ScreenshotCapture

    companion object {
        const val DEFAULT_SETTLE_MS = 250L
        const val DEFAULT_STABILITY_TIMEOUT_MS = 4_000L
    }
}
