package com.autobile.runtime.control

import com.autobile.core.model.Bounds
import com.autobile.core.model.Direction
import com.autobile.core.model.UiNode

/**
 * Performs actions on the screen.
 *
 * The counterpart to [com.autobile.runtime.perception.ScreenObserver]: together they are
 * the entire surface through which the executor touches the device, so a fake pair of
 * them is enough to exercise the execution loop end to end in a unit test.
 */
interface ScreenActuator {

    suspend fun click(node: UiNode): ActionResult

    suspend fun longPress(node: UiNode, durationMs: Long): ActionResult

    /** Taps a point. Used only where no node action is available. */
    suspend fun tapAt(bounds: Bounds): ActionResult

    suspend fun tapRatio(xRatio: Float, yRatio: Float): ActionResult

    suspend fun swipe(direction: Direction, distanceRatio: Float, durationMs: Long): ActionResult

    /** Scrolls [container] if it can scroll itself, otherwise swipes. */
    suspend fun scroll(container: UiNode?, direction: Direction): ActionResult

    suspend fun inputText(node: UiNode, value: String, clearExisting: Boolean): ActionResult

    fun pressBack(): ActionResult

    fun pressHome(): ActionResult

    fun launchApp(packageName: String, activity: String? = null): ActionResult
}
