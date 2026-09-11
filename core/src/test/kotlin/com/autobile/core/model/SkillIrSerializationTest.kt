package com.autobile.core.model

import com.autobile.core.common.AutobileJson
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.encodeToString
import org.junit.Test

/**
 * Skills are persisted as JSON and outlive the build that wrote them, so a lossless
 * round trip is a correctness requirement rather than a convenience.
 */
class SkillIrSerializationTest {

    private fun sampleSkill(): SemanticSkill = SemanticSkill(
        id = "skill-1",
        version = 3,
        name = "Daily Sales",
        goal = "Report previous-day net sales to #daily-sales",
        trigger = TriggerSpec.Time(hour = 9, minute = 0),
        variables = listOf(
            SkillVariable(
                name = "date",
                type = ValueType.DATE,
                binding = VariableBinding.RelativeDate(offsetDays = -1),
                description = "yesterday",
            ),
            SkillVariable(name = "netSales", type = ValueType.CURRENCY),
        ),
        constants = listOf(SkillConstant(name = "channel", value = "#daily-sales")),
        steps = listOf(
            SkillStep(
                id = "s1",
                intent = StepIntent.LAUNCH_APP,
                target = TargetSemantics(intentLabel = "BUSINESS_APP"),
                action = ActionSpec.LaunchApp("com.example.business"),
            ),
            SkillStep(
                id = "s2",
                intent = StepIntent.READ_VALUE,
                target = TargetSemantics(
                    intentLabel = "NET_SALES_FIELD",
                    synonyms = listOf("net sales", "순매출"),
                    locators = listOf(Locator(LocatorKind.RESOURCE_ID, "com.example.business:id/net_sales")),
                    valueSemantics = ValueSemantics("net sales", ValueType.CURRENCY, unit = "KRW"),
                ),
                action = ActionSpec.ReadValue("netSales"),
                validation = ValidationSpec(
                    mode = ValidationMode.VALUE,
                    valueConstraints = ValueConstraints(
                        fieldName = "net sales",
                        expectedType = ValueType.CURRENCY,
                        dateBinding = VariableBinding.RelativeDate(-1),
                    ),
                ),
            ),
            SkillStep(
                id = "s3",
                intent = StepIntent.SEND,
                target = TargetSemantics(intentLabel = "SEND_BUTTON"),
                action = ActionSpec.Click,
                validation = ValidationSpec(mode = ValidationMode.SEMANTIC, goalCritical = true),
            ),
        ),
        riskPolicy = RiskPolicy(categories = setOf(RiskCategory.MESSAGE_SEND)),
    )

    @Test
    fun `skill round-trips through json without loss`() {
        val original = sampleSkill()
        val decoded = AutobileJson.decodeFromString<SemanticSkill>(AutobileJson.encodeToString(original))
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `polymorphic actions keep their concrete type`() {
        val decoded = AutobileJson.decodeFromString<SemanticSkill>(AutobileJson.encodeToString(sampleSkill()))
        assertThat(decoded.steps[0].action).isInstanceOf(ActionSpec.LaunchApp::class.java)
        assertThat(decoded.steps[1].action).isInstanceOf(ActionSpec.ReadValue::class.java)
        assertThat(decoded.steps[2].action).isEqualTo(ActionSpec.Click)
    }

    @Test
    fun `unknown fields are tolerated so older builds can read newer documents`() {
        val json = AutobileJson.encodeToString(sampleSkill())
            .replaceFirst("{", """{"futureField":"ignored",""")
        val decoded = AutobileJson.decodeFromString<SemanticSkill>(json)
        assertThat(decoded.id).isEqualTo("skill-1")
    }

    @Test
    fun `relative date variables survive serialization as bindings not literals`() {
        val decoded = AutobileJson.decodeFromString<SemanticSkill>(AutobileJson.encodeToString(sampleSkill()))
        val binding = decoded.variables.first { it.name == "date" }.binding
        assertThat(binding).isEqualTo(VariableBinding.RelativeDate(offsetDays = -1))
    }
}
