package com.autobile.ai.context

import android.graphics.Bitmap
import android.graphics.Rect
import com.autobile.core.common.Logx
import com.autobile.core.model.Bounds
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.UiNode

/**
 * Reduces screen context before it reaches a model.
 *
 * Two problems are solved here at once. On-device models have small context windows,
 * so a whole accessibility tree simply will not fit; and when a request does escalate
 * to the network, whatever was not removed here is what leaves the device. Cutting the
 * context down in code — before any tier sees it — is therefore both a performance
 * measure and the last line of privacy defence.
 */
class ContextMinimizer(
    private val maxNodes: Int = DEFAULT_MAX_NODES,
    private val maxTextLength: Int = DEFAULT_MAX_TEXT_LENGTH,
) {

    /**
     * Selects the nodes most likely to be relevant to [terms].
     *
     * Scoring favours, in order: textual overlap with the search terms, being
     * interactive, and being large enough to be a real control rather than a spacer.
     */
    fun relevantNodes(
        snapshot: ScreenSnapshot,
        terms: List<String>,
        limit: Int = maxNodes,
    ): List<UiNode> {
        if (snapshot.nodes.isEmpty()) return emptyList()
        val normalisedTerms = terms.map { it.lowercase() }.filter { it.isNotBlank() }

        return snapshot.nodes
            .asSequence()
            .filter { it.visible && !it.bounds.isEmpty }
            .filter { it.label().isNotBlank() || it.isActionable() }
            .map { node -> node to score(node, normalisedTerms) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    private fun score(node: UiNode, terms: List<String>): Double {
        val label = node.label().lowercase()
        var score = 0.0

        for (term in terms) {
            if (label.isEmpty()) break
            when {
                label == term -> score += 10.0
                label.contains(term) -> score += 5.0
                term.contains(label) && label.length >= 2 -> score += 3.0
                else -> score += tokenOverlap(label, term)
            }
        }
        if (node.resourceId != null && terms.any { node.resourceId!!.lowercase().contains(it) }) score += 6.0
        if (node.clickable) score += 1.5
        if (node.editable) score += 1.0
        if (node.scrollable) score += 0.5
        if (!node.enabled) score -= 2.0
        // Very small nodes are usually decorations; very large ones are usually containers.
        val area = node.bounds.area
        if (area in MIN_USEFUL_AREA..MAX_USEFUL_AREA) score += 0.5
        return score
    }

    private fun tokenOverlap(label: String, term: String): Double {
        val labelTokens = label.split(TOKEN_SPLIT).filter { it.length > 1 }.toSet()
        val termTokens = term.split(TOKEN_SPLIT).filter { it.length > 1 }.toSet()
        if (labelTokens.isEmpty() || termTokens.isEmpty()) return 0.0
        val shared = labelTokens.intersect(termTokens).size
        return shared * 2.0
    }

    /**
     * Renders nodes as a compact numbered list for prompting.
     *
     * The index is what the model answers with, which keeps responses short and avoids
     * asking a small model to echo back a long identifier correctly.
     */
    fun renderNodes(nodes: List<UiNode>, maskSensitive: Boolean = true): String =
        nodes.mapIndexed { index, node ->
            val label = node.label().let { if (maskSensitive) Logx.redact(it) else it }.take(maxTextLength)
            val traits = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.scrollable) add("scrollable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
                if (!node.enabled) add("disabled")
            }
            buildString {
                append(index)
                append(". \"")
                append(label)
                append("\"")
                if (traits.isNotEmpty()) append(" [").append(traits.joinToString(",")).append("]")
            }
        }.joinToString("\n")

    /** One-line summary of a screen, used where the full node list is unnecessary. */
    fun describeScreen(snapshot: ScreenSnapshot, maskSensitive: Boolean = true): String {
        val texts = snapshot.allText()
            .asSequence()
            .filter { it.length in 1..maxTextLength }
            .distinct()
            .take(SCREEN_SUMMARY_TEXTS)
            .map { if (maskSensitive) Logx.redact(it) else it }
            .toList()
        return buildString {
            append("app=").append(snapshot.packageName)
            if (snapshot.windowTitle.isNotBlank()) append(" window=").append(snapshot.windowTitle)
            append("\ntext: ").append(texts.joinToString(" | "))
        }
    }

    /**
     * Crops a screenshot to the region that matters and scales it down.
     *
     * Sending a full-resolution screen is wasteful and exposes more than the question
     * requires; a padded crop around the region of interest answers the same question.
     */
    fun cropForInference(bitmap: Bitmap, focus: Bounds?, maxDimension: Int = DEFAULT_MAX_IMAGE_DIMENSION): Bitmap {
        val source = if (focus != null && !focus.isEmpty) {
            val padding = (maxOf(focus.width, focus.height) * CROP_PADDING_RATIO).toInt()
            val rect = Rect(
                (focus.left - padding).coerceAtLeast(0),
                (focus.top - padding).coerceAtLeast(0),
                (focus.right + padding).coerceAtMost(bitmap.width),
                (focus.bottom + padding).coerceAtMost(bitmap.height),
            )
            if (rect.width() > 0 && rect.height() > 0) {
                Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
            } else {
                bitmap
            }
        } else {
            bitmap
        }

        val longest = maxOf(source.width, source.height)
        if (longest <= maxDimension) return source
        val scale = maxDimension.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    /** Rough token estimate used to decide whether a tier's context window suffices. */
    fun estimateTokens(text: String): Int = (text.length / CHARS_PER_TOKEN) + 1

    companion object {
        const val DEFAULT_MAX_NODES = 24
        const val DEFAULT_MAX_TEXT_LENGTH = 80
        const val DEFAULT_MAX_IMAGE_DIMENSION = 768

        private const val SCREEN_SUMMARY_TEXTS = 20
        private const val MIN_USEFUL_AREA = 400
        private const val MAX_USEFUL_AREA = 1_500_000
        private const val CROP_PADDING_RATIO = 0.35
        private const val CHARS_PER_TOKEN = 4
        private val TOKEN_SPLIT = Regex("[^\\p{L}\\p{N}]+")
    }
}
