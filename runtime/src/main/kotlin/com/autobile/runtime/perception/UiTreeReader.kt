package com.autobile.runtime.perception

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import com.autobile.core.model.Bounds
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.UiNode

/**
 * Converts a live accessibility tree into an immutable [ScreenSnapshot].
 *
 * Traversal is bounded in both depth and node count. Some apps render thousands of
 * nodes, and walking all of them would cost more than the decision the snapshot is
 * being taken for; the caps keep perception cheap enough to run before every step.
 *
 * The snapshot is a plain data class with no framework references, which is what lets
 * the resolver, the executor and their tests work without a device.
 */
class UiTreeReader(
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxNodes: Int = DEFAULT_MAX_NODES,
) {

    fun read(
        root: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        windowTitle: String = "",
        capturedAt: Long = System.currentTimeMillis(),
    ): ScreenSnapshot {
        if (root == null) {
            return ScreenSnapshot(screenWidth = screenWidth, screenHeight = screenHeight, capturedAt = capturedAt)
        }
        val nodes = mutableListOf<UiNode>()
        traverse(root, parentId = null, indexPath = emptyList(), depth = 0, out = nodes)
        return ScreenSnapshot(
            packageName = root.packageName?.toString().orEmpty(),
            windowTitle = windowTitle,
            activityHint = root.className?.toString().orEmpty(),
            nodes = nodes,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            capturedAt = capturedAt,
        )
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        parentId: String?,
        indexPath: List<Int>,
        depth: Int,
        out: MutableList<UiNode>,
    ) {
        if (depth > maxDepth || out.size >= maxNodes) return

        val nodeId = if (indexPath.isEmpty()) "root" else indexPath.joinToString("-")
        val rect = Rect().also { runCatching { node.getBoundsInScreen(it) } }
        val text = node.text?.toString()?.takeIf { it.isNotBlank() }
        val description = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val resourceId = node.viewIdResourceName?.takeIf { it.isNotBlank() }

        // Structural containers with no label and no behaviour carry no information for
        // either the resolver or a model, so they are traversed but not recorded.
        val informative = text != null || description != null || resourceId != null ||
            node.isClickable || node.isEditable || node.isScrollable || node.isCheckable

        if (informative) {
            out += UiNode(
                nodeId = nodeId,
                resourceId = resourceId,
                text = text,
                contentDescription = description,
                hint = runCatching { node.hintText?.toString() }.getOrNull()?.takeIf { it.isNotBlank() },
                className = node.className?.toString(),
                packageName = node.packageName?.toString(),
                bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                clickable = node.isClickable,
                longClickable = node.isLongClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
                checkable = node.isCheckable,
                checked = node.isNodeChecked(),
                selected = node.isSelected,
                enabled = node.isEnabled,
                focused = node.isFocused,
                visible = node.isVisibleToUser && rect.width() > 0 && rect.height() > 0,
                depth = depth,
                indexPath = indexPath,
                parentId = parentId,
            )
        }

        val childCount = node.childCount
        for (index in 0 until childCount) {
            if (out.size >= maxNodes) return
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            traverse(
                node = child,
                parentId = if (informative) nodeId else parentId,
                indexPath = indexPath + index,
                depth = depth + 1,
                out = out,
            )
        }
    }

    /**
     * Reads the checked state across API levels.
     *
     * Android 16 replaced the boolean accessor with a tri-state one that also reports
     * partially-checked. Autobile only distinguishes checked from not, so partial is
     * treated as not checked rather than as a third case callers would have to handle.
     */
    private fun AccessibilityNodeInfo.isNodeChecked(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            checked == AccessibilityNodeInfo.CHECKED_STATE_TRUE
        } else {
            @Suppress("DEPRECATION")
            isChecked
        }

    companion object {
        const val DEFAULT_MAX_DEPTH = 40
        const val DEFAULT_MAX_NODES = 400
    }
}
