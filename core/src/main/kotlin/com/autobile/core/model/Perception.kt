package com.autobile.core.model

import kotlinx.serialization.Serializable

/**
 * A flattened, serialisable view of what is on screen at one instant.
 *
 * The accessibility tree is the primary perception source because it is cheap,
 * structured and available without any image processing. A screenshot is captured only
 * when the tree alone cannot identify the target, and [screenshotRef] is null whenever
 * that was not necessary.
 */
@Serializable
data class ScreenSnapshot(
    val packageName: String = "",
    val windowTitle: String = "",
    val activityHint: String = "",
    val nodes: List<UiNode> = emptyList(),
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val capturedAt: Long = 0L,
    val screenshotRef: String? = null,
    val secureWindow: Boolean = false,
) {
    val isEmpty: Boolean get() = nodes.isEmpty()

    fun interactiveNodes(): List<UiNode> = nodes.filter { it.clickable || it.editable || it.scrollable }

    fun allText(): List<String> =
        nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun containsText(needle: String, ignoreCase: Boolean = true): Boolean =
        allText().any { it.contains(needle, ignoreCase) }
}

/**
 * One accessibility node, reduced to the fields the resolver and the reasoning tiers
 * actually use.
 *
 * The reduction is deliberate: on-device models have small context windows, so a
 * narrow node shape is what makes it possible to describe a screen to one without
 * truncation.
 */
@Serializable
data class UiNode(
    val nodeId: String,
    val resourceId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val hint: String? = null,
    val className: String? = null,
    val packageName: String? = null,
    val bounds: Bounds = Bounds(),
    val clickable: Boolean = false,
    val longClickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val visible: Boolean = true,
    val depth: Int = 0,
    /** Stable-ish index path from the window root, used as a weak locator. */
    val indexPath: List<Int> = emptyList(),
    val parentId: String? = null,
) {
    /** Text a human (or a model) would use to name this element. */
    fun label(): String =
        listOfNotNull(text, contentDescription, hint)
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: resourceId?.substringAfterLast('/')?.replace('_', ' ')
            ?: className?.substringAfterLast('.')
            ?: ""

    fun hierarchyPath(): String = indexPath.joinToString("/")

    fun isActionable(): Boolean = enabled && visible && (clickable || longClickable || editable || scrollable)
}

@Serializable
data class Bounds(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Int get() = width.coerceAtLeast(0) * height.coerceAtLeast(0)
    val isEmpty: Boolean get() = width <= 0 || height <= 0

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom
}

/** Result of trying to observe the screen. */
sealed interface PerceptionResult {
    data class Success(val snapshot: ScreenSnapshot) : PerceptionResult

    /**
     * The window contains protected content and Android refused the capture.
     *
     * This is a terminal state for the step, by design: the app reports it and stops
     * rather than attempting to work around the protection.
     */
    data class BlockedSecureWindow(val packageName: String) : PerceptionResult

    data class Unavailable(val reason: String) : PerceptionResult
}
