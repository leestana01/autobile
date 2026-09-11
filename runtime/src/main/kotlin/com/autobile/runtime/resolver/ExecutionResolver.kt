package com.autobile.runtime.resolver

import android.graphics.Bitmap
import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.UiNode

/**
 * Finds the element a step is talking about.
 *
 * Resolution is staged from cheapest to most capable, and it stops at the first stage
 * that produces a confident answer:
 *
 * 1. **Cached locators** — the resource id or hierarchy path recorded when the skill was
 *    compiled. Free, and correct for the overwhelming majority of runs.
 * 2. **Textual matching** — the step's description and synonyms against on-screen labels.
 *    Still free, and handles the common case of a renamed or relocated control.
 * 3. **Inference** — a model picks from the shortlist. Reached only when meaning has to
 *    be interpreted rather than matched.
 * 4. **Vision** — the same question against a screenshot, for interfaces whose labels do
 *    not survive into the accessibility tree.
 *
 * The staging is what keeps a stable automation free of inference calls entirely, and it
 * is why a skill keeps working after a redesign that a recorded coordinate would not
 * survive.
 */
class ExecutionResolver(
    private val router: AiRuntimeRouter,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
) {

    suspend fun resolve(
        target: TargetSemantics,
        snapshot: ScreenSnapshot,
        screenshot: Bitmap? = null,
        allowInference: Boolean = true,
        allowVision: Boolean = true,
        localOnly: Boolean = false,
    ): Resolution {
        if (snapshot.nodes.isEmpty()) return Resolution.NotFound("The screen has no readable elements")

        matchByLocator(target, snapshot)?.let { return it }
        matchByText(target, snapshot)?.let { return it }

        if (!allowInference) {
            return Resolution.NeedsReasoning("No deterministic match for \"${target.intentLabel}\"")
        }

        val candidates = minimizer.relevantNodes(snapshot, target.matchTerms())
        if (candidates.isEmpty()) return Resolution.NotFound("No candidate elements on this screen")

        val prompt = AiTasks.elementMatchPrompt(
            targetDescription = target.description.ifBlank { target.intentLabel },
            synonyms = target.synonyms,
            renderedNodes = minimizer.renderNodes(candidates),
        )

        val routed = router.infer(
            label = "element-match",
            schema = AiTasks.elementMatch,
            prompt = prompt,
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                minConfidence = INFERENCE_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
                estimatedInputTokens = minimizer.estimateTokens(prompt),
            ),
        )

        routed.value?.takeIf { it.found && it.index in candidates.indices }?.let { match ->
            return Resolution.Found(
                node = candidates[match.index],
                resolver = ResolverKind.ACCESSIBILITY_NODE,
                tier = routed.tier,
                confidence = minOf(match.confidence, routed.confidence),
                explanation = match.reason.ifBlank { "matched by meaning" },
                usedCloud = routed.usedCloud,
                cloudWasDecisive = routed.cloudWasDecisive,
            )
        }

        if (!allowVision || screenshot == null) {
            return Resolution.NotFound("No element matches \"${target.intentLabel}\"")
        }
        return resolveVisually(target, snapshot, screenshot, candidates, localOnly)
    }

    /**
     * Last resort: ask about the screenshot itself.
     *
     * Some interfaces — canvas-drawn views, games, embedded web content — expose little
     * or nothing through the accessibility tree, and pixels are the only description
     * available. The shortlist is still supplied so the answer maps back to a real,
     * actionable node rather than to raw coordinates.
     */
    private suspend fun resolveVisually(
        target: TargetSemantics,
        snapshot: ScreenSnapshot,
        screenshot: Bitmap,
        candidates: List<UiNode>,
        localOnly: Boolean,
    ): Resolution {
        val prompt = AiTasks.elementMatchPrompt(
            targetDescription = target.description.ifBlank { target.intentLabel },
            synonyms = target.synonyms,
            renderedNodes = minimizer.renderNodes(candidates),
        )
        val routed = router.infer(
            label = "element-match-visual",
            schema = AiTasks.elementMatch,
            prompt = prompt,
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            image = minimizer.cropForInference(screenshot, null),
            requirements = InferenceRequirements(
                needsVision = true,
                minConfidence = INFERENCE_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
            ),
        )
        val match = routed.value
        return if (match != null && match.found && match.index in candidates.indices) {
            Resolution.Found(
                node = candidates[match.index],
                resolver = ResolverKind.VISION,
                tier = routed.tier,
                confidence = minOf(match.confidence, routed.confidence),
                explanation = match.reason.ifBlank { "matched visually" },
                usedCloud = routed.usedCloud,
                cloudWasDecisive = routed.cloudWasDecisive,
            )
        } else {
            Resolution.NotFound("No element matches \"${target.intentLabel}\", including visually")
        }
    }

    /**
     * Matches a cached locator against the live tree.
     *
     * Only the locators that identify an element rather than merely describe it are
     * trusted here. A resource id is assigned by the developer and survives layout
     * changes; a hierarchy path survives renaming. Text is handled at the next stage,
     * where a weaker match can be scored instead of accepted outright.
     */
    private fun matchByLocator(target: TargetSemantics, snapshot: ScreenSnapshot): Resolution.Found? {
        val byStrength = target.locators.sortedByDescending { it.strength }

        for (locator in byStrength.filter { it.kind == LocatorKind.RESOURCE_ID }) {
            snapshot.nodes.firstOrNull { it.visible && it.resourceId == locator.value }?.let { node ->
                return found(node, "resource id")
            }
        }

        for (locator in byStrength.filter { it.kind == LocatorKind.HIERARCHY_PATH }) {
            snapshot.nodes.firstOrNull { it.visible && it.hierarchyPath() == locator.value }?.let { node ->
                // A path only identifies the right element if what sits there still
                // looks like the recorded target, otherwise a reordered list silently
                // resolves to the wrong row.
                if (labelsAreCompatible(node, target)) return found(node, "hierarchy path")
            }
        }
        return null
    }

    /**
     * Scores on-screen labels against the target's known names.
     *
     * Exact and case-insensitive equality resolve immediately. Anything weaker is only
     * accepted when it is unambiguous: if two elements score equally well, the choice is
     * a judgement call and is handed to a reasoning tier rather than guessed.
     */
    private fun matchByText(target: TargetSemantics, snapshot: ScreenSnapshot): Resolution.Found? {
        val terms = target.matchTerms().map { it.lowercase() }.filter { it.isNotBlank() }
        if (terms.isEmpty()) return null

        val actionable = snapshot.nodes.filter { it.visible && it.enabled }
        if (actionable.isEmpty()) return null

        val scored = actionable
            .map { node -> node to textScore(node, terms) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }

        val best = scored.firstOrNull() ?: return null
        if (best.second >= EXACT_MATCH_SCORE) return found(best.first, "exact label", confidence = 0.95f)

        val runnerUp = scored.getOrNull(1)
        val isUnambiguous = runnerUp == null || best.second - runnerUp.second >= AMBIGUITY_MARGIN
        if (best.second >= STRONG_MATCH_SCORE && isUnambiguous) {
            return found(best.first, "label match", confidence = 0.8f)
        }
        return null
    }

    private fun textScore(node: UiNode, terms: List<String>): Double {
        val label = node.label().lowercase().trim()
        if (label.isEmpty()) return 0.0
        var score = 0.0
        for (term in terms) {
            when {
                label == term -> score += EXACT_MATCH_SCORE
                label.replace(WHITESPACE, "") == term.replace(WHITESPACE, "") -> score += EXACT_MATCH_SCORE - 1
                label.startsWith(term) || label.endsWith(term) -> score += 4.0
                label.contains(term) -> score += 3.0
            }
        }
        if (node.clickable) score += 0.5
        return score
    }

    /**
     * Guards a positional match against a wholesale content change: the path is only
     * trusted if the element there still shares vocabulary with the recorded target.
     */
    private fun labelsAreCompatible(node: UiNode, target: TargetSemantics): Boolean {
        val label = node.label().lowercase()
        if (label.isBlank()) return true
        return target.matchTerms().any { label.contains(it.lowercase()) || it.lowercase().contains(label) }
    }

    private fun found(node: UiNode, how: String, confidence: Float = 1f) = Resolution.Found(
        node = node,
        resolver = ResolverKind.ACCESSIBILITY_NODE,
        tier = RuntimeTier.DETERMINISTIC,
        confidence = confidence,
        explanation = how,
        usedCloud = false,
        cloudWasDecisive = false,
    )

    private companion object {
        const val EXACT_MATCH_SCORE = 10.0
        const val STRONG_MATCH_SCORE = 3.0
        const val AMBIGUITY_MARGIN = 1.5
        const val INFERENCE_CONFIDENCE_THRESHOLD = 0.6f
        val WHITESPACE = Regex("\\s+")
    }
}

sealed interface Resolution {
    data class Found(
        val node: UiNode,
        val resolver: ResolverKind,
        val tier: RuntimeTier,
        val confidence: Float,
        val explanation: String,
        val usedCloud: Boolean,
        val cloudWasDecisive: Boolean,
    ) : Resolution {
        /** True when no inference was needed, which is the intended common case. */
        val wasDeterministic: Boolean get() = tier == RuntimeTier.DETERMINISTIC
    }

    /** The element is absent, and no tier believes otherwise. */
    data class NotFound(val reason: String) : Resolution

    /** A decision is needed but reasoning was not permitted for this attempt. */
    data class NeedsReasoning(val reason: String) : Resolution
}
