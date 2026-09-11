package com.autobile.runtime.teach

import com.autobile.ai.task.SegmentedStep
import com.autobile.ai.task.StepRole
import com.autobile.ai.task.TraceSegmentation
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.EventClassification
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.TraceEvent
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.routerWith
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Segmentation decides what becomes an automation. Discarding a step the user needed is
 * far worse than keeping a redundant one, and the default-keep behaviour is asserted.
 */
class TraceSegmenterTest {

    private fun event(
        index: Int,
        action: ObservedAction,
        label: String = "Item $index",
        beforeWindow: String = "Home",
        afterWindow: String = "Home",
    ) = TraceEvent(
        id = "e$index",
        timestamp = index.toLong(),
        packageName = "com.example.business",
        windowContext = afterWindow,
        before = ScreenSnapshot(packageName = "com.example.business", windowTitle = beforeWindow),
        after = ScreenSnapshot(packageName = "com.example.business", windowTitle = afterWindow),
        action = action,
        targetNode = node("n$index", text = label),
    )

    private fun trace(vararg events: TraceEvent) = DemonstrationTrace(
        id = "t",
        startedAt = 0,
        endedAt = 100,
        events = events.toList(),
    )

    @Test
    fun `an empty trace segments to nothing`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(trace())
        assertThat(result.events).isEmpty()
        assertThat(result.usedInference).isFalse()
    }

    @Test
    fun `opening an app is navigation, not the point of the task`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(trace(event(0, ObservedAction.AppOpen("com.example.business"))))
        assertThat(result.events.first().classification).isEqualTo(EventClassification.NAVIGATION)
    }

    @Test
    fun `a window change carries no intent of its own`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(trace(event(0, ObservedAction.WindowChange("Reports"))))
        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
    }

    @Test
    fun `typing is always essential`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(trace(event(0, ObservedAction.TextInput("hello"))))
        assertThat(result.events.first().classification).isEqualTo(EventClassification.ESSENTIAL)
    }

    @Test
    fun `a tap undone by an immediate back is discarded`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider()))
        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.Click, beforeWindow = "Home", afterWindow = "Settings"),
                event(1, ObservedAction.Back, beforeWindow = "Settings", afterWindow = "Home"),
            ),
        )
        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
        assertThat(result.discardedCount).isAtLeast(1)
    }

    @Test
    fun `structural rules alone avoid an inference call`() = runTest {
        val provider = ScriptedProvider()
        val segmenter = TraceSegmenter(routerWith(provider))

        segmenter.segment(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.TextInput("hello")),
                event(2, ObservedAction.Scroll(com.autobile.core.model.Direction.DOWN)),
            ),
        )

        assertThat(provider.requestedLabels).isEmpty()
    }

    @Test
    fun `ambiguous taps are classified by inference`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "trace-segmentation",
            TraceSegmentation(
                steps = listOf(SegmentedStep(index = 0, role = StepRole.NOISE, why = "browsed then left")),
                confidence = 0.8f,
            ),
        )
        val segmenter = TraceSegmenter(routerWith(provider))

        val result = segmenter.segment(trace(event(0, ObservedAction.Click)))

        assertThat(provider.requestedLabels).contains("trace-segmentation")
        assertThat(result.events.first().classification).isEqualTo(EventClassification.NOISE)
    }

    @Test
    fun `steps are kept when no runtime can classify them`() = runTest {
        val segmenter = TraceSegmenter(routerWith(ScriptedProvider(available = false)))
        val result = segmenter.segment(trace(event(0, ObservedAction.Click)))
        assertThat(result.events.first().classification).isEqualTo(EventClassification.ESSENTIAL)
        assertThat(result.compilable()).hasSize(1)
    }

    @Test
    fun `only noise is excluded from compilation`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "trace-segmentation",
            TraceSegmentation(
                steps = listOf(SegmentedStep(index = 1, role = StepRole.ESSENTIAL, why = "taps send")),
                confidence = 0.9f,
            ),
        )
        val segmenter = TraceSegmenter(routerWith(provider))

        val result = segmenter.segment(
            trace(
                event(0, ObservedAction.AppOpen("com.example.business")),
                event(1, ObservedAction.Click, label = "Send"),
                event(2, ObservedAction.WindowChange("Sent")),
            ),
        )

        assertThat(result.compilable().map { it.id }).containsExactly("e0", "e1").inOrder()
    }
}
