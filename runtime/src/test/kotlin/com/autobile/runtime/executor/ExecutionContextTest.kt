package com.autobile.runtime.executor

import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConstant
import com.autobile.core.model.SkillVariable
import com.autobile.core.model.ValueRef
import com.autobile.core.model.ValueType
import com.autobile.core.model.VariableBinding
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

/**
 * Relative bindings are what keep a compiled skill from reporting the same day forever,
 * so their resolution is pinned to a fixed date here rather than the real clock.
 */
class ExecutionContextTest {

    private val today = LocalDate.of(2026, 9, 11)

    private fun context(
        variables: List<SkillVariable> = emptyList(),
        constants: List<SkillConstant> = emptyList(),
        triggerPayload: Map<String, String> = emptyMap(),
        userInputs: Map<String, String> = emptyMap(),
    ) = ExecutionContext(
        skill = SemanticSkill(
            id = "s",
            version = 1,
            name = "n",
            goal = "g",
            variables = variables,
            constants = constants,
        ),
        triggerPayload = triggerPayload,
        userInputs = userInputs,
        today = { today },
    )

    @Test
    fun `a relative date resolves against today, not the day it was recorded`() {
        val context = context(
            variables = listOf(
                SkillVariable(
                    name = "date",
                    type = ValueType.DATE,
                    binding = VariableBinding.RelativeDate(offsetDays = -1),
                ),
            ),
        )
        assertThat(context.resolveVariable("date")).isEqualTo("2026-09-10")
    }

    @Test
    fun `a relative date honours its recorded format`() {
        val context = context(
            variables = listOf(
                SkillVariable(
                    name = "date",
                    binding = VariableBinding.RelativeDate(offsetDays = -7, pattern = "yyyy/MM/dd"),
                ),
            ),
        )
        assertThat(context.resolveVariable("date")).isEqualTo("2026/09/04")
    }

    @Test
    fun `constants resolve to their fixed value`() {
        val context = context(constants = listOf(SkillConstant("channel", "#daily-sales")))
        assertThat(context.resolve(ValueRef.Constant("channel"))).isEqualTo("#daily-sales")
    }

    @Test
    fun `an extracted value overrides its binding once it has been read`() {
        val context = context(
            variables = listOf(SkillVariable(name = "netSales", binding = VariableBinding.Extracted())),
        )
        assertThat(context.resolveVariable("netSales")).isNull()
        context.putExtracted("netSales", "2,481,000")
        assertThat(context.resolveVariable("netSales")).isEqualTo("2,481,000")
    }

    @Test
    fun `trigger payload fields are available to the run`() {
        val context = context(
            variables = listOf(
                SkillVariable(name = "orderId", binding = VariableBinding.TriggerPayload("notification.title")),
            ),
            triggerPayload = mapOf("notification.title" to "Order 81234 delivered"),
        )
        assertThat(context.resolveVariable("orderId")).isEqualTo("Order 81234 delivered")
    }

    @Test
    fun `templates substitute variables and constants together`() {
        val context = context(
            variables = listOf(
                SkillVariable(name = "date", binding = VariableBinding.RelativeDate(offsetDays = -1)),
            ),
            constants = listOf(SkillConstant("channel", "#daily-sales")),
        )
        context.putExtracted("netSales", "2,481,000")

        val rendered = context.renderTemplate("{date} net sales: {netSales} → {channel}")

        assertThat(rendered).isEqualTo("2026-09-10 net sales: 2,481,000 → #daily-sales")
    }

    @Test
    fun `an unresolvable placeholder is left visible rather than silently blanked`() {
        val rendered = context().renderTemplate("total: {missing}")
        assertThat(rendered).isEqualTo("total: {missing}")
    }

    @Test
    fun `unresolved placeholders can be listed before a step runs`() {
        val context = context(constants = listOf(SkillConstant("channel", "#daily-sales")))
        val unresolved = context.unresolvedPlaceholders("{channel} {netSales} {date}")
        assertThat(unresolved).containsExactly("netSales", "date")
    }

    @Test
    fun `user supplied input resolves its variable`() {
        val context = context(
            variables = listOf(SkillVariable(name = "query", binding = VariableBinding.UserInput("What to search"))),
            userInputs = mapOf("query" to "quarterly report"),
        )
        assertThat(context.resolveVariable("query")).isEqualTo("quarterly report")
    }
}
