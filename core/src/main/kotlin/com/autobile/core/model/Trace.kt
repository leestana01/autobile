package com.autobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The raw record of what the user did during a demonstration.
 *
 * A trace is evidence, not a program. It is never executed directly: the compiler
 * consumes it and produces a [SemanticSkill], which is what actually runs. Keeping the
 * two separate is what makes a taught automation adapt to a changed UI instead of
 * replaying stale coordinates.
 */
@Serializable
data class DemonstrationTrace(
    val id: String,
    val label: String = "",
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val events: List<TraceEvent> = emptyList(),
) {
    val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    val packages: List<String> get() = events.map { it.packageName }.filter { it.isNotEmpty() }.distinct()
}

@Serializable
data class TraceEvent(
    val id: String,
    val timestamp: Long,
    val packageName: String = "",
    val windowContext: String = "",
    val before: ScreenSnapshot? = null,
    val after: ScreenSnapshot? = null,
    val action: ObservedAction,
    val targetNode: UiNode? = null,
    val coordinates: Point? = null,
    val inputValue: String? = null,
    val stateTransition: StateTransition? = null,
    val screenshotBeforeRef: String? = null,
    val screenshotAfterRef: String? = null,
    /** Assigned by trace segmentation. Noise is kept for audit but excluded from compilation. */
    val classification: EventClassification = EventClassification.UNCLASSIFIED,
    val classificationReason: String = "",
)

@Serializable
data class Point(val x: Int = 0, val y: Int = 0)

@Serializable
data class StateTransition(
    val fromPackage: String = "",
    val toPackage: String = "",
    val fromWindow: String = "",
    val toWindow: String = "",
) {
    val changedScreen: Boolean get() = fromWindow != toWindow || fromPackage != toPackage
}

@Serializable
sealed interface ObservedAction {
    @Serializable @SerialName("click") data object Click : ObservedAction
    @Serializable @SerialName("long_click") data object LongClick : ObservedAction
    @Serializable @SerialName("scroll") data class Scroll(val direction: Direction) : ObservedAction
    @Serializable @SerialName("text_input") data class TextInput(val value: String) : ObservedAction
    @Serializable @SerialName("back") data object Back : ObservedAction
    @Serializable @SerialName("home") data object Home : ObservedAction
    @Serializable @SerialName("app_open") data class AppOpen(val packageName: String) : ObservedAction
    @Serializable @SerialName("window_change") data class WindowChange(val window: String) : ObservedAction
    @Serializable @SerialName("select") data object Select : ObservedAction
}

/**
 * What role an observed event plays in the skill being learned.
 *
 * Real demonstrations contain mis-taps, back-outs and exploratory scrolling. Compiling
 * those verbatim produces a brittle automation, so segmentation classifies each event
 * and only [ESSENTIAL] and [NAVIGATION] events become steps.
 */
enum class EventClassification {
    UNCLASSIFIED,
    /** Carries the user's intent; becomes a step. */
    ESSENTIAL,
    /** Required to reach an essential step; becomes a step. */
    NAVIGATION,
    /** Exploration, undo or mis-tap. Retained for audit, excluded from the compiled skill. */
    NOISE,
    /** Reads or inspects without changing state. */
    OBSERVATION,
}
