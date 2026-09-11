package com.autobile.ai.context

import com.autobile.core.model.Bounds
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.UiNode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Whatever survives minimisation is what a model sees, and on an escalated request it
 * is also what leaves the device. Both the ranking and the masking are load-bearing.
 */
class ContextMinimizerTest {

    private val minimizer = ContextMinimizer()

    private fun node(
        id: String,
        text: String? = null,
        resourceId: String? = null,
        clickable: Boolean = true,
        bounds: Bounds = Bounds(0, 0, 300, 120),
    ) = UiNode(
        nodeId = id,
        text = text,
        resourceId = resourceId,
        clickable = clickable,
        bounds = bounds,
    )

    private fun snapshot(vararg nodes: UiNode) = ScreenSnapshot(
        packageName = "com.example.business",
        windowTitle = "Reports",
        nodes = nodes.toList(),
        screenWidth = 1080,
        screenHeight = 2400,
    )

    @Test
    fun `an exact label match ranks above a partial one`() {
        val ranked = minimizer.relevantNodes(
            snapshot(
                node("a", text = "Monthly Sales"),
                node("b", text = "Daily Sales"),
                node("c", text = "Settings"),
            ),
            terms = listOf("daily sales"),
        )
        assertThat(ranked.first().text).isEqualTo("Daily Sales")
    }

    @Test
    fun `a matching resource id contributes to ranking`() {
        val ranked = minimizer.relevantNodes(
            snapshot(
                node("a", text = "Something else"),
                node("b", text = "", resourceId = "com.example:id/net_sales"),
            ),
            terms = listOf("net_sales"),
        )
        assertThat(ranked.first().nodeId).isEqualTo("b")
    }

    @Test
    fun `invisible and zero sized nodes are dropped`() {
        val ranked = minimizer.relevantNodes(
            snapshot(
                node("visible", text = "Reports"),
                node("empty", text = "Reports", bounds = Bounds(0, 0, 0, 0)),
                node("hidden", text = "Reports").copy(visible = false),
            ),
            terms = listOf("reports"),
        )
        assertThat(ranked.map { it.nodeId }).containsExactly("visible")
    }

    @Test
    fun `the node list is capped so it fits a small context window`() {
        val many = (0 until 100).map { node("n$it", text = "Item $it") }
        val ranked = minimizer.relevantNodes(snapshot(*many.toTypedArray()), terms = listOf("item"), limit = 10)
        assertThat(ranked).hasSize(10)
    }

    @Test
    fun `rendered nodes are numbered so the model answers with an index`() {
        val rendered = minimizer.renderNodes(listOf(node("a", text = "Send"), node("b", text = "Cancel")))
        assertThat(rendered).startsWith("0. \"Send\"")
        assertThat(rendered).contains("1. \"Cancel\"")
    }

    @Test
    fun `rendered nodes carry the traits needed to choose between them`() {
        val rendered = minimizer.renderNodes(
            listOf(node("a", text = "Amount", clickable = false).copy(editable = true)),
        )
        assertThat(rendered).contains("editable")
    }

    @Test
    fun `sensitive text is masked before it can reach any provider`() {
        val rendered = minimizer.renderNodes(listOf(node("a", text = "card 4111 1111 1111 1111")))
        assertThat(rendered).doesNotContain("4111")
    }

    @Test
    fun `screen descriptions mask sensitive text as well`() {
        val description = minimizer.describeScreen(snapshot(node("a", text = "otp: 993211")))
        assertThat(description).doesNotContain("993211")
        assertThat(description).contains("com.example.business")
    }

    @Test
    fun `token estimation grows with input size`() {
        assertThat(minimizer.estimateTokens("x".repeat(400))).isGreaterThan(minimizer.estimateTokens("x".repeat(40)))
    }

    @Test
    fun `an empty screen produces no candidates`() {
        assertThat(minimizer.relevantNodes(ScreenSnapshot(), terms = listOf("anything"))).isEmpty()
    }
}
