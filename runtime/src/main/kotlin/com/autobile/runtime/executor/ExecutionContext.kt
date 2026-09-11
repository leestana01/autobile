package com.autobile.runtime.executor

import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConstant
import com.autobile.core.model.SkillVariable
import com.autobile.core.model.ValueRef
import com.autobile.core.model.VariableBinding
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The mutable state of one skill run: variable values, extracted readings and the
 * trigger payload that started it.
 *
 * Variables are resolved here rather than baked into the skill so that a compiled skill
 * stays valid over time. A demonstration recorded on one day stored "yesterday" as a
 * binding, and this is where that binding becomes an actual date on the day it runs.
 */
class ExecutionContext(
    private val skill: SemanticSkill,
    private val triggerPayload: Map<String, String> = emptyMap(),
    private val userInputs: Map<String, String> = emptyMap(),
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) {
    private val extracted = mutableMapOf<String, String>()
    private val constants: Map<String, SkillConstant> = skill.constants.associateBy { it.name }
    private val variables: Map<String, SkillVariable> = skill.variables.associateBy { it.name }

    /** Records a value read from the screen by a step. */
    fun putExtracted(name: String, value: String) {
        extracted[name] = value
    }

    fun extractedValue(name: String): String? = extracted[name]

    fun allExtracted(): Map<String, String> = extracted.toMap()

    /** Resolves a reference to the string that should be typed or sent. */
    fun resolve(ref: ValueRef): String? = when (ref) {
        is ValueRef.Literal -> ref.value
        is ValueRef.Constant -> constants[ref.name]?.value
        is ValueRef.Variable -> resolveVariable(ref.name)
        is ValueRef.Template -> renderTemplate(ref.template)
    }

    fun resolveVariable(name: String): String? {
        extracted[name]?.let { return it }
        val variable = variables[name] ?: return null
        return when (val binding = variable.binding) {
            is VariableBinding.RelativeDate ->
                today().plusDays(binding.offsetDays.toLong())
                    .format(DateTimeFormatter.ofPattern(binding.pattern))

            is VariableBinding.UserInput -> userInputs[name] ?: variable.exampleValue
            is VariableBinding.TriggerPayload -> triggerPayload[binding.field]
            is VariableBinding.Extracted -> null
        }
    }

    /**
     * Substitutes `{name}` placeholders.
     *
     * An unresolvable placeholder is left in place rather than blanked: a message that
     * visibly contains `{netSales}` is a bug a user will report, whereas one with a
     * silent gap looks deliberate and goes unnoticed.
     */
    fun renderTemplate(template: String): String =
        PLACEHOLDER.replace(template) { match ->
            val name = match.groupValues[1]
            resolveVariable(name) ?: constants[name]?.value ?: match.value
        }

    /** Names referenced by [template] that cannot currently be resolved. */
    fun unresolvedPlaceholders(template: String): List<String> =
        PLACEHOLDER.findAll(template)
            .map { it.groupValues[1] }
            .filter { resolveVariable(it) == null && constants[it] == null }
            .toList()

    private companion object {
        val PLACEHOLDER = Regex("\\{([A-Za-z0-9_]+)\\}")
    }
}
