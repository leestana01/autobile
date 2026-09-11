package com.autobile.runtime.validation

import com.autobile.ai.task.OutcomeCheck
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.core.model.ValueConstraints
import com.autobile.core.model.ValueType
import com.autobile.core.model.VariableBinding
import com.autobile.runtime.ScriptedProvider
import com.autobile.runtime.node
import com.autobile.runtime.routerWith
import com.autobile.runtime.screen
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

/**
 * Validation is what stops a run from reporting success it did not achieve, so the
 * failure paths matter more here than the happy ones.
 */
class ValidationEngineTest {

    private fun engine(provider: ScriptedProvider = ScriptedProvider()) =
        ValidationEngine(routerWith(provider))

    @Test
    fun `a missing required text fails structural validation`() = runTest {
        val outcome = engine().validate(
            spec = ValidationSpec(mode = ValidationMode.STRUCTURAL),
            expected = ExpectedState(requiredTexts = listOf("Daily Sales")),
            snapshot = screen(nodes = arrayOf(node("a", text = "Inbox"))),
        )
        assertThat(outcome.passed).isFalse()
    }

    @Test
    fun `the wrong foreground app fails structural validation`() = runTest {
        val outcome = engine().validate(
            spec = ValidationSpec(mode = ValidationMode.STRUCTURAL),
            expected = ExpectedState(requiredPackage = "com.example.business"),
            snapshot = screen(packageName = "com.other.app", nodes = arrayOf(node("a", text = "Hello"))),
        )
        assertThat(outcome.passed).isFalse()
        assertThat(outcome.reason).contains("com.other.app")
    }

    @Test
    fun `forbidden text on screen fails validation`() = runTest {
        val outcome = engine().validate(
            spec = ValidationSpec(mode = ValidationMode.STRUCTURAL),
            expected = ExpectedState(forbiddenTexts = listOf("Error")),
            snapshot = screen(nodes = arrayOf(node("a", text = "Error: could not load"))),
        )
        assertThat(outcome.passed).isFalse()
    }

    @Test
    fun `an empty reading fails value validation`() = runTest {
        val outcome = engine().validate(
            spec = ValidationSpec(
                mode = ValidationMode.VALUE,
                valueConstraints = ValueConstraints(fieldName = "Net sales"),
            ),
            expected = ExpectedState(),
            snapshot = screen(nodes = arrayOf(node("a", text = "Net sales"))),
            extractedValue = "",
        )
        assertThat(outcome.passed).isFalse()
    }

    @Test
    fun `a non numeric reading fails a currency constraint`() = runTest {
        val outcome = engine().validate(
            spec = ValidationSpec(
                mode = ValidationMode.VALUE,
                valueConstraints = ValueConstraints(fieldName = "Net sales", expectedType = ValueType.CURRENCY),
            ),
            expected = ExpectedState(),
            snapshot = screen(nodes = arrayOf(node("a", text = "Net sales"))),
            extractedValue = "not available",
        )
        assertThat(outcome.passed).isFalse()
    }

    @Test
    fun `a value read from a screen without the field fails validation`() = runTest {
        val outcome = engine().validate(
            spec = ValidationSpec(
                mode = ValidationMode.VALUE,
                valueConstraints = ValueConstraints(fieldName = "Net sales", expectedType = ValueType.CURRENCY),
            ),
            expected = ExpectedState(),
            snapshot = screen(nodes = arrayOf(node("a", text = "Gross sales"), node("b", text = "3,000,000"))),
            extractedValue = "3,000,000",
        )
        assertThat(outcome.passed).isFalse()
        assertThat(outcome.reason).contains("Net sales")
    }

    @Test
    fun `a reading from the wrong date fails even when the number is valid`() = runTest {
        val today = LocalDate.of(2026, 9, 11)
        val outcome = engine().validate(
            spec = ValidationSpec(
                mode = ValidationMode.VALUE,
                valueConstraints = ValueConstraints(
                    fieldName = "Net sales",
                    expectedType = ValueType.CURRENCY,
                    dateBinding = VariableBinding.RelativeDate(offsetDays = -1),
                ),
            ),
            expected = ExpectedState(),
            snapshot = screen(
                nodes = arrayOf(
                    node("a", text = "Net sales"),
                    node("b", text = "2026-09-04"),
                    node("c", text = "2,481,000"),
                ),
            ),
            extractedValue = "2,481,000",
            today = today,
        )
        assertThat(outcome.passed).isFalse()
        assertThat(outcome.reason).contains("2026-09-10")
    }

    @Test
    fun `a reading from the expected date passes`() = runTest {
        val today = LocalDate.of(2026, 9, 11)
        val outcome = engine().validate(
            spec = ValidationSpec(
                mode = ValidationMode.VALUE,
                valueConstraints = ValueConstraints(
                    fieldName = "Net sales",
                    expectedType = ValueType.CURRENCY,
                    dateBinding = VariableBinding.RelativeDate(offsetDays = -1),
                ),
            ),
            expected = ExpectedState(),
            snapshot = screen(
                nodes = arrayOf(
                    node("a", text = "Net sales"),
                    node("b", text = "2026-09-10"),
                    node("c", text = "2,481,000"),
                ),
            ),
            extractedValue = "2,481,000",
            today = today,
        )
        assertThat(outcome.passed).isTrue()
    }

    @Test
    fun `a screen showing no date is inconclusive rather than wrong`() = runTest {
        val outcome = engine().validate(
            spec = ValidationSpec(
                mode = ValidationMode.VALUE,
                valueConstraints = ValueConstraints(
                    fieldName = "Net sales",
                    expectedType = ValueType.CURRENCY,
                    dateBinding = VariableBinding.RelativeDate(offsetDays = -1),
                ),
            ),
            expected = ExpectedState(),
            snapshot = screen(nodes = arrayOf(node("a", text = "Net sales"), node("b", text = "2,481,000"))),
            extractedValue = "2,481,000",
            today = LocalDate.of(2026, 9, 11),
        )
        assertThat(outcome.passed).isTrue()
    }

    @Test
    fun `an unverifiable outcome is reported as failure rather than success`() = runTest {
        // No runtime can answer the semantic question.
        val outcome = engine(ScriptedProvider(available = false)).validate(
            spec = ValidationSpec(mode = ValidationMode.SEMANTIC, expectation = "the message was sent"),
            expected = ExpectedState(),
            snapshot = screen(nodes = arrayOf(node("a", text = "Chat"))),
        )
        assertThat(outcome.passed).isFalse()
        assertThat(outcome.reason).contains("could not be verified")
    }

    @Test
    fun `a confirmed semantic outcome passes`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "outcome-check",
            OutcomeCheck(satisfied = true, confidence = 0.9f, observed = "message visible in the channel"),
        )
        val outcome = engine(provider).validate(
            spec = ValidationSpec(mode = ValidationMode.SEMANTIC, expectation = "the message was sent"),
            expected = ExpectedState(),
            snapshot = screen(nodes = arrayOf(node("a", text = "Chat"))),
        )
        assertThat(outcome.passed).isTrue()
    }

    @Test
    fun `a rejected semantic outcome fails and records what was seen`() = runTest {
        val provider = ScriptedProvider().answerWith(
            "outcome-check",
            OutcomeCheck(satisfied = false, confidence = 0.9f, observed = "still on the compose screen"),
        )
        val outcome = engine(provider).validate(
            spec = ValidationSpec(mode = ValidationMode.SEMANTIC, expectation = "the message was sent"),
            expected = ExpectedState(),
            snapshot = screen(nodes = arrayOf(node("a", text = "Compose"))),
        )
        assertThat(outcome.passed).isFalse()
        assertThat(outcome.observed).contains("compose")
    }

    @Test
    fun `unmet post-conditions prevent a run from being reported as complete`() = runTest {
        val outcome = engine().validateGoal(
            postconditions = listOf(
                com.autobile.core.model.Condition(
                    description = "the report was posted",
                    kind = com.autobile.core.model.ConditionKind.STRUCTURAL,
                    requiredTexts = listOf("Sent"),
                ),
            ),
            snapshot = screen(nodes = arrayOf(node("a", text = "Draft"))),
        )
        assertThat(outcome.passed).isFalse()
    }

    @Test
    fun `displayed numbers are parsed through their formatting`() {
        val engine = engine()
        assertThat(engine.parseNumber("2,481,000")).isEqualTo(2481000.0)
        assertThat(engine.parseNumber("₩ 2,481,000")).isEqualTo(2481000.0)
        assertThat(engine.parseNumber("not a number")).isNull()
    }
}
