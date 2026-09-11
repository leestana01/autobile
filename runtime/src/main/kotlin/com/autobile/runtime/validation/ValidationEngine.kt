package com.autobile.runtime.validation

import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.core.model.Condition
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationOutcome
import com.autobile.core.model.ValidationSpec
import com.autobile.core.model.ValueConstraints
import com.autobile.core.model.ValueType
import com.autobile.core.model.VariableBinding
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Decides whether a step actually achieved what it was supposed to.
 *
 * A tap succeeding is not the same as a step succeeding: the tap can land, the app can
 * accept it, and the wrong screen can open. Every step that matters therefore states a
 * post-condition, and this class checks it against what is on screen afterwards.
 *
 * Structural checks run first and cost nothing. Inference is used only for expectations
 * that cannot be reduced to the presence of text.
 */
class ValidationEngine(
    private val router: AiRuntimeRouter,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
) {

    suspend fun validate(
        spec: ValidationSpec,
        expected: ExpectedState,
        snapshot: ScreenSnapshot,
        extractedValue: String? = null,
        localOnly: Boolean = false,
        today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    ): ValidationOutcome = when (spec.mode) {
        ValidationMode.NONE -> ValidationOutcome(spec.mode, passed = true, reason = "no validation required")
        ValidationMode.STRUCTURAL -> validateStructure(spec, expected, snapshot)
        ValidationMode.VALUE -> validateValue(spec, extractedValue, snapshot, today)
        ValidationMode.SEMANTIC -> validateSemantically(spec, expected, snapshot, localOnly)
    }

    /** Checks package, required text and forbidden text — all directly observable. */
    private fun validateStructure(
        spec: ValidationSpec,
        expected: ExpectedState,
        snapshot: ScreenSnapshot,
    ): ValidationOutcome {
        if (expected.isEmpty) {
            return ValidationOutcome(spec.mode, passed = true, reason = "no expectation declared")
        }
        expected.requiredPackage?.let { pkg ->
            if (snapshot.packageName != pkg) {
                return ValidationOutcome(
                    spec.mode,
                    passed = false,
                    reason = "expected $pkg but the foreground app is ${snapshot.packageName}",
                    observed = snapshot.packageName,
                )
            }
        }
        val missing = expected.requiredTexts.filterNot { snapshot.containsText(it) }
        if (missing.isNotEmpty()) {
            return ValidationOutcome(
                spec.mode,
                passed = false,
                reason = "screen does not show ${missing.joinToString(", ")}",
            )
        }
        val forbidden = expected.forbiddenTexts.filter { snapshot.containsText(it) }
        if (forbidden.isNotEmpty()) {
            return ValidationOutcome(
                spec.mode,
                passed = false,
                reason = "screen shows ${forbidden.joinToString(", ")}",
            )
        }
        return ValidationOutcome(spec.mode, passed = true, reason = "expected screen confirmed", confidence = 1f)
    }

    /**
     * Validates a reading against its declared constraints.
     *
     * Checking the value alone is not enough. A number can be well-formed, in range, and
     * still be the wrong row's figure or yesterday's report shown again today, so the
     * field label and the date context are verified alongside it.
     */
    private fun validateValue(
        spec: ValidationSpec,
        extractedValue: String?,
        snapshot: ScreenSnapshot,
        today: LocalDate,
    ): ValidationOutcome {
        val constraints = spec.valueConstraints
            ?: return ValidationOutcome(spec.mode, passed = false, reason = "value validation has no constraints")

        if (extractedValue.isNullOrBlank()) {
            return if (constraints.nonEmpty) {
                ValidationOutcome(spec.mode, passed = false, reason = "no value was read")
            } else {
                ValidationOutcome(spec.mode, passed = true, reason = "empty value accepted")
            }
        }

        constraints.regex?.let { pattern ->
            if (!Regex(pattern).containsMatchIn(extractedValue)) {
                return ValidationOutcome(
                    spec.mode,
                    passed = false,
                    reason = "value does not match the expected format",
                    observed = extractedValue,
                )
            }
        }

        if (constraints.expectedType == ValueType.NUMBER || constraints.expectedType == ValueType.CURRENCY) {
            val numeric = parseNumber(extractedValue)
                ?: return ValidationOutcome(
                    spec.mode,
                    passed = false,
                    reason = "value is not numeric",
                    observed = extractedValue,
                )
            constraints.minValue?.let {
                if (numeric < it) {
                    return ValidationOutcome(
                        spec.mode,
                        passed = false,
                        reason = "value $numeric is below the expected minimum",
                        observed = extractedValue,
                    )
                }
            }
            constraints.maxValue?.let {
                if (numeric > it) {
                    return ValidationOutcome(
                        spec.mode,
                        passed = false,
                        reason = "value $numeric is above the expected maximum",
                        observed = extractedValue,
                    )
                }
            }
        }

        if (!snapshot.containsText(constraints.fieldName)) {
            return ValidationOutcome(
                spec.mode,
                passed = false,
                reason = "the screen does not show a \"${constraints.fieldName}\" field",
                observed = extractedValue,
                confidence = 0.5f,
            )
        }

        contextDateMismatch(constraints, snapshot, today)?.let { reason ->
            return ValidationOutcome(spec.mode, passed = false, reason = reason, observed = extractedValue)
        }

        return ValidationOutcome(
            spec.mode,
            passed = true,
            reason = "${constraints.fieldName} read successfully",
            observed = extractedValue,
            confidence = 1f,
        )
    }

    /**
     * Detects a value taken from the wrong day.
     *
     * Only reports a mismatch when the screen shows a date it recognises *and* that date
     * is not the expected one. A screen with no visible date is inconclusive, not wrong.
     */
    private fun contextDateMismatch(
        constraints: ValueConstraints,
        snapshot: ScreenSnapshot,
        today: LocalDate,
    ): String? {
        val binding = constraints.dateBinding as? VariableBinding.RelativeDate ?: return null
        val expectedDate = today.plusDays(binding.offsetDays.toLong())
        val expectedRenderings = DATE_PATTERNS.map { expectedDate.format(DateTimeFormatter.ofPattern(it)) }
        if (expectedRenderings.any { snapshot.containsText(it) }) return null

        val visibleDates = DATE_IN_TEXT.findAll(snapshot.allText().joinToString(" ")).map { it.value }.toList()
        if (visibleDates.isEmpty()) return null
        return "the screen shows ${visibleDates.first()} but the value should be for $expectedDate"
    }

    /** Asks a reasoning tier whether a described outcome occurred. */
    private suspend fun validateSemantically(
        spec: ValidationSpec,
        expected: ExpectedState,
        snapshot: ScreenSnapshot,
        localOnly: Boolean,
    ): ValidationOutcome {
        val expectation = spec.expectation.ifBlank { expected.description }
        if (expectation.isBlank()) {
            return validateStructure(spec.copy(mode = ValidationMode.STRUCTURAL), expected, snapshot)
                .copy(mode = spec.mode)
        }

        val description = minimizer.describeScreen(snapshot)
        val routed = router.infer(
            label = "outcome-check",
            schema = AiTasks.outcomeCheck,
            prompt = AiTasks.outcomeCheckPrompt(expectation, description),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                minConfidence = SEMANTIC_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
                estimatedInputTokens = minimizer.estimateTokens(description),
            ),
        )

        val check = routed.value
            ?: return ValidationOutcome(
                spec.mode,
                passed = false,
                // No runtime could judge the outcome. Reporting this as a pass would be
                // the single most damaging failure the system can produce.
                reason = "the outcome could not be verified",
                confidence = 0f,
            )

        return ValidationOutcome(
            mode = spec.mode,
            passed = check.satisfied,
            reason = if (check.satisfied) "outcome confirmed" else "expected outcome did not occur",
            observed = check.observed,
            confidence = minOf(check.confidence, routed.confidence),
        )
    }

    /**
     * Checks the skill's own post-conditions.
     *
     * Distinct from per-step validation: individual steps can all succeed while the
     * overall goal remains unmet, and that combination must not be reported as success.
     */
    suspend fun validateGoal(
        postconditions: List<Condition>,
        snapshot: ScreenSnapshot,
        localOnly: Boolean = false,
    ): ValidationOutcome {
        if (postconditions.isEmpty()) {
            return ValidationOutcome(ValidationMode.NONE, passed = true, reason = "no post-conditions declared")
        }
        for (condition in postconditions) {
            val outcome = when (condition.kind) {
                com.autobile.core.model.ConditionKind.STRUCTURAL, com.autobile.core.model.ConditionKind.VALUE ->
                    validateStructure(
                        ValidationSpec(mode = ValidationMode.STRUCTURAL),
                        ExpectedState(
                            requiredPackage = condition.packageName,
                            requiredTexts = condition.requiredTexts,
                            forbiddenTexts = condition.forbiddenTexts,
                            description = condition.description,
                        ),
                        snapshot,
                    )

                com.autobile.core.model.ConditionKind.SEMANTIC ->
                    validateSemantically(
                        ValidationSpec(mode = ValidationMode.SEMANTIC, expectation = condition.description),
                        ExpectedState(description = condition.description),
                        snapshot,
                        localOnly,
                    )
            }
            if (!outcome.passed) return outcome
        }
        return ValidationOutcome(ValidationMode.SEMANTIC, passed = true, reason = "goal confirmed", confidence = 1f)
    }

    /** Parses a displayed number, tolerating thousands separators and currency symbols. */
    fun parseNumber(text: String): Double? {
        val cleaned = text.replace(NON_NUMERIC, "").replace(",", "")
        return cleaned.toDoubleOrNull()
    }

    private companion object {
        const val SEMANTIC_CONFIDENCE_THRESHOLD = 0.6f
        val NON_NUMERIC = Regex("[^0-9.,\\-]")
        val DATE_IN_TEXT = Regex("\\d{4}[-/.]\\d{1,2}[-/.]\\d{1,2}")
        val DATE_PATTERNS = listOf("yyyy-MM-dd", "yyyy/MM/dd", "yyyy.MM.dd", "M/d", "MM/dd", "d MMM", "MMM d")
    }
}
