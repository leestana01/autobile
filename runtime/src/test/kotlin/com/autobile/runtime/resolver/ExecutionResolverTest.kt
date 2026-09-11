package com.autobile.runtime.resolver

import com.autobile.ai.task.ElementMatch
import com.autobile.core.model.Bounds
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.TargetSemantics
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.routerWith
import com.autobile.runtime.screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The resolver decides whether a run costs an inference call or nothing at all, and
 * whether a skill survives an app update. Both properties are asserted directly.
 */
class ExecutionResolverTest {

    private fun resolver(provider: ScriptedProvider = ScriptedProvider()) =
        ExecutionResolver(routerWith(provider)) to provider

    @Test
    fun `a matching resource id resolves without any inference`() = runTest {
        val (resolver, provider) = resolver()
        val target = TargetSemantics(
            intentLabel = "DAILY_SALES",
            locators = listOf(Locator(LocatorKind.RESOURCE_ID, "com.example:id/daily_sales")),
        )
        val snapshot = screen(
            nodes = arrayOf(
                node("a", text = "Monthly", resourceId = "com.example:id/monthly"),
                node("b", text = "Daily Sales", resourceId = "com.example:id/daily_sales"),
            ),
        )

        val resolution = resolver.resolve(target, snapshot)

        assertThat(resolution).isInstanceOf(Resolution.Found::class.java)
        val found = resolution as Resolution.Found
        assertThat(found.node.nodeId).isEqualTo("b")
        assertThat(found.wasDeterministic).isTrue()
        assertThat(provider.requestedLabels).isEmpty()
    }

    @Test
    fun `an exact label match resolves without inference when the id has changed`() = runTest {
        val (resolver, provider) = resolver()
        val target = TargetSemantics(
            intentLabel = "Daily Sales",
            description = "Daily Sales",
            locators = listOf(Locator(LocatorKind.RESOURCE_ID, "com.example:id/old_id")),
        )
        val snapshot = screen(
            nodes = arrayOf(
                node("a", text = "Settings"),
                node("b", text = "Daily Sales", resourceId = "com.example:id/new_id"),
            ),
        )

        val found = resolver.resolve(target, snapshot) as Resolution.Found

        assertThat(found.node.nodeId).isEqualTo("b")
        assertThat(found.tier).isEqualTo(RuntimeTier.DETERMINISTIC)
        assertThat(provider.requestedLabels).isEmpty()
    }

    @Test
    fun `two equally plausible labels are escalated rather than guessed`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "element-match",
            ElementMatch(index = 0, confidence = 0.9f, reason = "first sales entry"),
        )
        val (resolver, _) = resolver(provider)
        val target = TargetSemantics(intentLabel = "sales", description = "sales")
        val snapshot = screen(
            nodes = arrayOf(
                node("a", text = "Sales report"),
                node("b", text = "Sales summary"),
            ),
        )

        resolver.resolve(target, snapshot)

        assertThat(provider.requestedLabels).contains("element-match")
    }

    @Test
    fun `a hierarchy path is rejected when the element there is unrelated`() = runTest {
        val (resolver, _) = resolver()
        val target = TargetSemantics(
            intentLabel = "Daily Sales",
            description = "Daily Sales",
            locators = listOf(Locator(LocatorKind.HIERARCHY_PATH, "0-2-1")),
        )
        val snapshot = screen(
            nodes = arrayOf(node("a", text = "Delete account", indexPath = listOf(0, 2, 1))),
        )

        val resolution = resolver.resolve(target, snapshot, allowInference = false)

        assertThat(resolution).isNotInstanceOf(Resolution.Found::class.java)
    }

    @Test
    fun `a hierarchy path is accepted when the element there still matches`() = runTest {
        val (resolver, _) = resolver()
        val target = TargetSemantics(
            intentLabel = "Daily Sales",
            description = "Daily Sales",
            locators = listOf(Locator(LocatorKind.HIERARCHY_PATH, "0-2-1")),
        )
        val snapshot = screen(
            nodes = arrayOf(node("a", text = "Daily Sales", indexPath = listOf(0, 2, 1))),
        )

        val found = resolver.resolve(target, snapshot, allowInference = false) as Resolution.Found

        assertThat(found.node.nodeId).isEqualTo("a")
    }

    @Test
    fun `inference selects the element when nothing matches by text`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "element-match",
            ElementMatch(index = 1, confidence = 0.85f, reason = "Reports leads to sales"),
        )
        val (resolver, _) = resolver(provider)
        val target = TargetSemantics(intentLabel = "DAILY_SALES", description = "the daily sales page")
        val snapshot = screen(
            nodes = arrayOf(
                node("a", text = "Inbox"),
                node("b", text = "Reports"),
            ),
        )

        val found = resolver.resolve(target, snapshot) as Resolution.Found

        assertThat(found.node.nodeId).isEqualTo("b")
        assertThat(found.tier).isEqualTo(RuntimeTier.DEVICE_AI)
    }

    @Test
    fun `an absent element is reported as not found rather than substituted`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "element-match",
            ElementMatch(index = -1, confidence = 0.9f, reason = "not present"),
        )
        val (resolver, _) = resolver(provider)
        val snapshot = screen(nodes = arrayOf(node("a", text = "Inbox")))

        val resolution = resolver.resolve(TargetSemantics(intentLabel = "Send"), snapshot)

        assertThat(resolution).isInstanceOf(Resolution.NotFound::class.java)
    }

    @Test
    fun `an out of range index from the model is rejected`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "element-match",
            ElementMatch(index = 99, confidence = 0.99f, reason = "fabricated"),
        )
        val (resolver, _) = resolver(provider)
        val snapshot = screen(nodes = arrayOf(node("a", text = "Inbox")))

        val resolution = resolver.resolve(TargetSemantics(intentLabel = "Send"), snapshot)

        assertThat(resolution).isInstanceOf(Resolution.NotFound::class.java)
    }

    @Test
    fun `inference is not attempted when the caller forbids it`() = runTest {
        val provider = ScriptedProvider()
        val (resolver, _) = resolver(provider)
        val snapshot = screen(nodes = arrayOf(node("a", text = "Inbox")))

        val resolution = resolver.resolve(
            TargetSemantics(intentLabel = "Send"),
            snapshot,
            allowInference = false,
        )

        assertThat(resolution).isInstanceOf(Resolution.NeedsReasoning::class.java)
        assertThat(provider.requestedLabels).isEmpty()
    }

    @Test
    fun `an empty screen resolves to not found`() = runTest {
        val (resolver, _) = resolver()
        val resolution = resolver.resolve(TargetSemantics(intentLabel = "Send"), screen())
        assertThat(resolution).isInstanceOf(Resolution.NotFound::class.java)
    }

    @Test
    fun `a disabled element does not satisfy a text match`() = runTest {
        val (resolver, _) = resolver()
        val snapshot = screen(
            nodes = arrayOf(node("a", text = "Send", bounds = Bounds(0, 0, 200, 80)).copy(enabled = false)),
        )

        val resolution = resolver.resolve(
            TargetSemantics(intentLabel = "Send", description = "Send"),
            snapshot,
            allowInference = false,
        )

        assertThat(resolution).isNotInstanceOf(Resolution.Found::class.java)
    }
}
