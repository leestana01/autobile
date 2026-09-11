package com.autobile.runtime.teach

import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.ai.task.StepRole
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.EventClassification
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.TaskComplexity
import com.autobile.core.model.TraceEvent

/**
 * Separates the steps that carry the user's intent from the ones that do not.
 *
 * A real demonstration is not a clean script. People tap the wrong thing, back out,
 * scroll around looking for something, and open the wrong tab first. Compiling all of
 * that verbatim produces an automation that faithfully reproduces the user's mistakes.
 *
 * Structural rules run first and catch the unambiguous cases — an action immediately
 * undone, a screen entered and left without doing anything. Only what remains genuinely
 * ambiguous is sent to a reasoning tier, which keeps most teaching sessions free of
 * inference entirely.
 */
class TraceSegmenter(private val router: AiRuntimeRouter) {

    suspend fun segment(trace: DemonstrationTrace, localOnly: Boolean = false): SegmentedTrace {
        if (trace.events.isEmpty()) return SegmentedTrace(emptyList(), usedInference = false, usedCloud = false)

        val structural = applyStructuralRules(trace.events)
        val ambiguous = structural.withIndex().filter { it.value.classification == EventClassification.UNCLASSIFIED }

        if (ambiguous.isEmpty()) {
            return SegmentedTrace(structural, usedInference = false, usedCloud = false)
        }

        val rendered = renderForInference(structural)
        val routed = router.infer(
            label = "trace-segmentation",
            schema = AiTasks.traceSegmentation,
            prompt = AiTasks.traceSegmentationPrompt(rendered),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                // A long demonstration exceeds what a small model can weigh at once, so
                // this is one of the few places escalation is expected rather than a
                // symptom of something going wrong.
                complexity = TaskComplexity.MODERATE,
                needsLongContext = structural.size > LONG_TRACE_EVENTS,
                minConfidence = SEGMENTATION_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
            ),
            maxOutputTokens = SEGMENTATION_OUTPUT_TOKENS,
        )

        val classified = routed.value
        if (classified == null) {
            // Without a verdict, every unclassified event is kept. Dropping a step the
            // user actually needed is far worse than keeping a redundant one.
            return SegmentedTrace(
                events = structural.map {
                    if (it.classification == EventClassification.UNCLASSIFIED) {
                        it.copy(classification = EventClassification.ESSENTIAL, classificationReason = "kept by default")
                    } else {
                        it
                    }
                },
                usedInference = true,
                usedCloud = routed.usedCloud,
            )
        }

        val byIndex = classified.steps.associateBy { it.index }
        val merged = structural.mapIndexed { index, event ->
            if (event.classification != EventClassification.UNCLASSIFIED) return@mapIndexed event
            val verdict = byIndex[index]
            event.copy(
                classification = verdict?.role?.toClassification() ?: EventClassification.ESSENTIAL,
                classificationReason = verdict?.why.orEmpty(),
            )
        }
        return SegmentedTrace(merged, usedInference = true, usedCloud = routed.usedCloud)
    }

    /**
     * Classifies what can be decided from the shape of the trace alone.
     *
     * These rules are cheap, deterministic and explain themselves, which makes them
     * preferable to inference wherever they apply.
     */
    private fun applyStructuralRules(events: List<TraceEvent>): List<TraceEvent> {
        val out = events.toMutableList()

        for (index in out.indices) {
            val event = out[index]
            val next = out.getOrNull(index + 1)

            val classification = when {
                // Opening an app is always how the user got somewhere, never the point.
                event.action is ObservedAction.AppOpen -> EventClassification.NAVIGATION

                // A window change with no interaction of its own is a side effect of the
                // tap before it, already recorded.
                event.action is ObservedAction.WindowChange -> EventClassification.NOISE

                // An action undone by an immediate back was a mistake.
                next?.action is ObservedAction.Back && event.action is ObservedAction.Click &&
                    returnedToSameScreen(event, next) -> EventClassification.NOISE

                // Scrolling locates something; the tap that follows is the intent.
                event.action is ObservedAction.Scroll -> EventClassification.NAVIGATION

                event.action is ObservedAction.TextInput -> EventClassification.ESSENTIAL

                else -> EventClassification.UNCLASSIFIED
            }

            out[index] = event.copy(
                classification = classification,
                classificationReason = classification.structuralReason(),
            )
        }
        return out
    }

    /** True when the step after an action returned to the screen it started on. */
    private fun returnedToSameScreen(event: TraceEvent, next: TraceEvent): Boolean {
        val before = event.before?.windowTitle ?: return false
        val afterNext = next.after?.windowTitle ?: return false
        return before == afterNext
    }

    private fun renderForInference(events: List<TraceEvent>): String =
        events.mapIndexed { index, event ->
            val label = event.targetNode?.label().orEmpty().ifBlank { event.action.describe() }
            val app = event.packageName.substringAfterLast('.')
            "$index. [$app] ${event.action.describe()} \"${com.autobile.core.common.Logx.redact(label)}\""
        }.joinToString("\n")

    private fun StepRole.toClassification(): EventClassification = when (this) {
        StepRole.ESSENTIAL -> EventClassification.ESSENTIAL
        StepRole.NAVIGATION -> EventClassification.NAVIGATION
        StepRole.NOISE -> EventClassification.NOISE
        StepRole.OBSERVATION -> EventClassification.OBSERVATION
    }

    private fun EventClassification.structuralReason(): String = when (this) {
        EventClassification.NAVIGATION -> "moves toward the goal"
        EventClassification.NOISE -> "undone or superseded"
        EventClassification.ESSENTIAL -> "changes state"
        EventClassification.OBSERVATION -> "reads without changing anything"
        EventClassification.UNCLASSIFIED -> ""
    }

    private companion object {
        const val SEGMENTATION_CONFIDENCE_THRESHOLD = 0.5f
        const val SEGMENTATION_OUTPUT_TOKENS = 1_024
        const val LONG_TRACE_EVENTS = 20
    }
}

data class SegmentedTrace(
    val events: List<TraceEvent>,
    val usedInference: Boolean,
    val usedCloud: Boolean,
) {
    /** The events that become steps, in order. */
    fun compilable(): List<TraceEvent> = events.filter {
        it.classification == EventClassification.ESSENTIAL ||
            it.classification == EventClassification.NAVIGATION ||
            it.classification == EventClassification.OBSERVATION
    }

    val discardedCount: Int get() = events.count { it.classification == EventClassification.NOISE }
}
