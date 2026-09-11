package com.autobile.ai.task

import com.autobile.ai.provider.JsonExtractor
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Schemas are the boundary between model output and actions taken on a user's phone,
 * so they have to reject malformed answers rather than pass them through.
 */
class AiTaskSchemaTest {

    private fun <T : Any> parse(schema: com.autobile.ai.provider.ResponseSchema<T>, raw: String): T? =
        JsonExtractor.extract(raw)?.let { schema.parse(it) }

    @Test
    fun `element match parses a selection`() {
        val match = parse(AiTasks.elementMatch, """{"index":3,"confidence":0.88,"reason":"under Reports"}""")!!
        assertThat(match.found).isTrue()
        assertThat(match.index).isEqualTo(3)
    }

    @Test
    fun `element match represents no match without inventing one`() {
        val match = parse(AiTasks.elementMatch, """{"index":-1,"confidence":0.2,"reason":"not present"}""")!!
        assertThat(match.found).isFalse()
    }

    @Test
    fun `element match rejects an out of range confidence`() {
        val match = parse(AiTasks.elementMatch, """{"index":1,"confidence":4.0}""")!!
        assertThat(AiTasks.elementMatch.validate(match)).isNotNull()
    }

    @Test
    fun `value extraction rejects a found flag with no value`() {
        val extracted = parse(AiTasks.valueExtraction, """{"found":true,"value":"","fieldLabel":"Net"}""")!!
        assertThat(AiTasks.valueExtraction.validate(extracted)).isNotNull()
    }

    @Test
    fun `value extraction keeps display formatting intact`() {
        val extracted = parse(
            AiTasks.valueExtraction,
            """{"found":true,"value":"2,481,000","fieldLabel":"Net sales","confidence":0.9}""",
        )!!
        assertThat(extracted.value).isEqualTo("2,481,000")
        assertThat(AiTasks.valueExtraction.validate(extracted)).isNull()
    }

    @Test
    fun `goal inference rejects an empty goal`() {
        val goal = parse(AiTasks.goalInference, """{"name":"Something","goal":"","summary":""}""")!!
        assertThat(AiTasks.goalInference.validate(goal)).isNotNull()
    }

    @Test
    fun `variable analysis recognises a relative date`() {
        val analysis = parse(
            AiTasks.variableAnalysis,
            """{"variables":[{"name":"date","observedValue":"2026-09-10","meaning":"yesterday","relativeDays":-1}],
               "constants":[{"name":"channel","value":"#daily-sales","why":"fixed"}],"confidence":0.8}""",
        )!!
        assertThat(analysis.variables).hasSize(1)
        assertThat(analysis.variables.first().isRelativeDate).isTrue()
        assertThat(analysis.constants.first().value).isEqualTo("#daily-sales")
    }

    @Test
    fun `variable analysis drops unnamed entries`() {
        val analysis = parse(
            AiTasks.variableAnalysis,
            """{"variables":[{"name":"","observedValue":"x"}],"constants":[]}""",
        )!!
        assertThat(analysis.variables).isEmpty()
    }

    @Test
    fun `an unrecognised segmentation role keeps the step rather than discarding it`() {
        val segmentation = parse(
            AiTasks.traceSegmentation,
            """{"steps":[{"index":0,"role":"who knows","why":""}],"confidence":0.5}""",
        )!!
        assertThat(segmentation.steps.first().role).isEqualTo(StepRole.ESSENTIAL)
    }

    @Test
    fun `an unrecognised recovery action gives up rather than guessing`() {
        val proposal = parse(AiTasks.recoveryProposal, """{"action":"teleport","index":9}""")!!
        assertThat(proposal.action).isEqualTo(RecoveryAction.GIVE_UP)
    }

    @Test
    fun `recovery proposals parse a scroll direction`() {
        val proposal = parse(
            AiTasks.recoveryProposal,
            """{"action":"scroll","index":-1,"direction":"down","reason":"list continues","confidence":0.7}""",
        )!!
        assertThat(proposal.action).isEqualTo(RecoveryAction.SCROLL)
        assertThat(proposal.direction).isEqualTo("down")
    }

    @Test
    fun `skill edit flags a change of meaning`() {
        val edit = parse(
            AiTasks.skillEdit,
            """{"field":"value_field","newValue":"net sales","meaningChanged":true,"summary":"Report net instead of gross"}""",
        )!!
        assertThat(edit.meaningChanged).isTrue()
        assertThat(edit.field).isEqualTo(SkillEditField.VALUE_FIELD)
    }

    @Test
    fun `notification match extracts trigger parameters`() {
        val match = parse(
            AiTasks.notificationMatch,
            """{"matches":true,"confidence":0.9,"extracted":[{"name":"orderId","value":"81234"}]}""",
        )!!
        assertThat(match.matches).isTrue()
        assertThat(match.extracted["orderId"]).isEqualTo("81234")
    }

    @Test
    fun `prompt contract asks for bare json`() {
        val contract = AiTasks.elementMatch.promptContract()
        assertThat(contract).contains("JSON only")
        assertThat(contract).contains("index")
    }
}
