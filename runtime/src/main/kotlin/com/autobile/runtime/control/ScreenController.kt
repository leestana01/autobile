package com.autobile.runtime.control

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.autobile.core.common.Logx
import com.autobile.core.model.Bounds
import com.autobile.core.model.Direction
import com.autobile.core.model.UiNode
import com.autobile.runtime.accessibility.AccessibilityBridge
import kotlinx.coroutines.delay

/**
 * Performs actions on the screen.
 *
 * Where a node exposes a semantic action, that action is used rather than a synthesised
 * touch at its coordinates. A node action is delivered to the element that owns it, so
 * it keeps working when the element moves, is partially covered, or sits inside a
 * scrolling container — none of which a coordinate tap survives. Coordinates are the
 * fallback for elements that expose nothing.
 */
class ScreenController(private val context: Context) {

    /**
     * Clicks [node].
     *
     * Falls back through the node's clickable ancestors before resorting to a tap: list
     * rows commonly expose their label on a non-clickable child of the clickable row.
     */
    suspend fun click(node: UiNode): ActionResult {
        val target = findLiveNode(node) ?: return ActionResult.Failed("Element is no longer on screen")
        clickableSelfOrAncestor(target)?.let { clickable ->
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return ActionResult.Performed("node click")
            }
        }
        return tapAt(node.bounds)
    }

    suspend fun longPress(node: UiNode, durationMs: Long): ActionResult {
        val target = findLiveNode(node)
        if (target != null && target.isLongClickable &&
            target.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        ) {
            return ActionResult.Performed("node long click")
        }
        val centre = node.bounds
        if (centre.isEmpty) return ActionResult.Failed("Element has no measurable bounds")
        return gesture(
            buildPath(centre.centerX.toFloat(), centre.centerY.toFloat()),
            durationMs,
            "long press",
        )
    }

    /** Taps a point. Used only where no node action is available. */
    suspend fun tapAt(bounds: Bounds): ActionResult {
        if (bounds.isEmpty) return ActionResult.Failed("Element has no measurable bounds")
        return gesture(
            buildPath(bounds.centerX.toFloat(), bounds.centerY.toFloat()),
            TAP_DURATION_MS,
            "tap",
        )
    }

    suspend fun tapRatio(xRatio: Float, yRatio: Float): ActionResult {
        val metrics = context.resources.displayMetrics
        val x = (metrics.widthPixels * xRatio.coerceIn(0f, 1f))
        val y = (metrics.heightPixels * yRatio.coerceIn(0f, 1f))
        return gesture(buildPath(x, y), TAP_DURATION_MS, "tap")
    }

    /**
     * Swipes across the screen.
     *
     * The travel is inset from the edges so the gesture does not land on the system
     * back or navigation areas, where it would be consumed instead of delivered.
     */
    suspend fun swipe(direction: Direction, distanceRatio: Float, durationMs: Long): ActionResult {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val centreX = width / 2
        val centreY = height / 2
        val travelX = width * distanceRatio.coerceIn(0.05f, 0.8f) / 2
        val travelY = height * distanceRatio.coerceIn(0.05f, 0.8f) / 2

        val stroke = when (direction) {
            Direction.UP -> Stroke(centreX, centreY + travelY, centreX, centreY - travelY)
            Direction.DOWN -> Stroke(centreX, centreY - travelY, centreX, centreY + travelY)
            Direction.LEFT -> Stroke(centreX + travelX, centreY, centreX - travelX, centreY)
            Direction.RIGHT -> Stroke(centreX - travelX, centreY, centreX + travelX, centreY)
        }

        val path = Path().apply {
            moveTo(
                stroke.startX.coerceIn(EDGE_INSET, width - EDGE_INSET),
                stroke.startY.coerceIn(EDGE_INSET, height - EDGE_INSET),
            )
            lineTo(
                stroke.endX.coerceIn(EDGE_INSET, width - EDGE_INSET),
                stroke.endY.coerceIn(EDGE_INSET, height - EDGE_INSET),
            )
        }
        return gesture(path, durationMs, "swipe ${direction.name.lowercase()}")
    }

    /**
     * Scrolls a scrollable container, preferring the node's own scroll action.
     *
     * A container's scroll action moves exactly that container; a swipe moves whatever
     * happens to be under the finger, which on a screen with nested scrollers is often
     * the wrong thing.
     */
    suspend fun scroll(container: UiNode?, direction: Direction): ActionResult {
        if (container != null) {
            val live = findLiveNode(container)
            if (live != null && live.isScrollable) {
                val action = when (direction) {
                    Direction.DOWN, Direction.RIGHT -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    Direction.UP, Direction.LEFT -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                }
                if (live.performAction(action)) return ActionResult.Performed("node scroll")
            }
        }
        // Swiping down moves content up, so the gesture is the inverse of the intent.
        val gestureDirection = when (direction) {
            Direction.DOWN -> Direction.UP
            Direction.UP -> Direction.DOWN
            Direction.LEFT -> Direction.RIGHT
            Direction.RIGHT -> Direction.LEFT
        }
        return swipe(gestureDirection, SCROLL_DISTANCE_RATIO, SCROLL_DURATION_MS)
    }

    /**
     * Types [value] into an editable node.
     *
     * Uses the text-setting action rather than synthesising key events: it is atomic,
     * it does not depend on the soft keyboard being visible, and it cannot interleave
     * with the app's own input handling.
     */
    suspend fun inputText(node: UiNode, value: String, clearExisting: Boolean): ActionResult {
        val target = findLiveNode(node) ?: return ActionResult.Failed("Text field is no longer on screen")
        val editable = editableSelfOrAncestor(target)
            ?: return ActionResult.Failed("Target does not accept text input")

        editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val text = if (clearExisting) value else (editable.text?.toString().orEmpty() + value)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            return ActionResult.Performed("set text")
        }
        return ActionResult.Failed("Text could not be entered")
    }

    fun pressBack(): ActionResult {
        val service = AccessibilityBridge.require() ?: return ActionResult.Failed(NO_SERVICE)
        return if (service.pressBack()) ActionResult.Performed("back") else ActionResult.Failed("Back was rejected")
    }

    fun pressHome(): ActionResult {
        val service = AccessibilityBridge.require() ?: return ActionResult.Failed(NO_SERVICE)
        return if (service.pressHome()) ActionResult.Performed("home") else ActionResult.Failed("Home was rejected")
    }

    /** Launches an app by package name, optionally at a specific activity. */
    fun launchApp(packageName: String, activity: String? = null): ActionResult {
        val intent = if (activity != null) {
            Intent().apply {
                setClassName(packageName, activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }
        } ?: return ActionResult.Failed("$packageName is not installed")

        return try {
            context.startActivity(intent)
            ActionResult.Performed("launched $packageName")
        } catch (e: Exception) {
            Logx.w("Could not launch $packageName", e)
            ActionResult.Failed("Could not launch $packageName")
        }
    }

    private suspend fun gesture(path: Path, durationMs: Long, label: String): ActionResult {
        val service = AccessibilityBridge.require() ?: return ActionResult.Failed(NO_SERVICE)
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        val delivered = service.dispatchGestureAwait(description)
        if (!delivered) return ActionResult.Failed("Gesture was not delivered")
        // Give the target a moment to begin reacting before the caller observes again.
        delay(POST_GESTURE_SETTLE_MS)
        return ActionResult.Performed(label)
    }

    private fun buildPath(x: Float, y: Float): Path = Path().apply { moveTo(x, y) }

    /**
     * Re-finds a node in the live tree.
     *
     * The snapshot the caller holds is a copy taken earlier; the framework object it
     * came from may already be stale. Matching is attempted by resource id, then by
     * label, then by hierarchy position, in decreasing order of reliability.
     */
    private fun findLiveNode(node: UiNode): AccessibilityNodeInfo? {
        val root = AccessibilityBridge.require()?.activeRoot() ?: return null

        node.resourceId?.let { id ->
            val matches = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull().orEmpty()
            matches.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        node.text?.takeIf { it.isNotBlank() }?.let { text ->
            val matches = runCatching { root.findAccessibilityNodeInfosByText(text) }.getOrNull().orEmpty()
            matches.firstOrNull { it.isVisibleToUser }?.let { return it }
        }
        return nodeAtPath(root, node.indexPath)
    }

    private fun nodeAtPath(root: AccessibilityNodeInfo, path: List<Int>): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = root
        for (index in path) {
            current = runCatching { current?.getChild(index) }.getOrNull() ?: return null
        }
        return current
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isClickable && current.isEnabled) return current
            current = current.parent
            hops++
        }
        return null
    }

    private fun editableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.isEditable) return current
            current = current.parent
            hops++
        }
        return null
    }

    private companion object {
        const val NO_SERVICE = "Accessibility access is not granted"
        const val TAP_DURATION_MS = 60L
        const val SCROLL_DURATION_MS = 320L
        const val SCROLL_DISTANCE_RATIO = 0.55f
        const val POST_GESTURE_SETTLE_MS = 120L
        const val EDGE_INSET = 24f
        const val MAX_ANCESTOR_HOPS = 6
    }
}

/** Start and end points of a swipe, in screen pixels. */
private data class Stroke(val startX: Float, val startY: Float, val endX: Float, val endY: Float)

sealed interface ActionResult {
    data class Performed(val how: String) : ActionResult
    data class Failed(val reason: String) : ActionResult

    val succeeded: Boolean get() = this is Performed

    val describe: String
        get() = when (this) {
            is Performed -> how
            is Failed -> reason
        }
}
