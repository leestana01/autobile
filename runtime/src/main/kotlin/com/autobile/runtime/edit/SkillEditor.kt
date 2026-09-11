package com.autobile.runtime.edit

import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.ai.task.SkillEdit
import com.autobile.ai.task.SkillEditField
import com.autobile.core.common.TimeSource
import com.autobile.core.data.SkillStore
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConstant
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TriggerSpec
import com.autobile.runtime.trigger.TriggerScheduler

/** Applies a bounded, plain-language change to a saved automation. */
class SkillEditor(
    private val router: AiRuntimeRouter,
    private val skillStore: SkillStore,
    private val scheduler: TriggerScheduler,
    private val time: TimeSource = TimeSource.System,
) {
    suspend fun preview(skillId: String, request: String, localOnly: Boolean): SkillEditPreview {
        val skill = skillStore.get(skillId) ?: return SkillEditPreview.Rejected("Automation not found")
        return preview(skill, request, localOnly)
    }

    /**
     * Previews a change against a skill the caller already holds.
     *
     * Used while reviewing a freshly compiled demonstration, before it has an entry in
     * the store: a misreading of what the user meant is easiest to catch at that point,
     * and hardest to notice once the automation has been running for a week.
     */
    suspend fun preview(skill: SemanticSkill, request: String, localOnly: Boolean): SkillEditPreview {
        if (request.isBlank()) return SkillEditPreview.Rejected("Describe the change you want")

        parseDeterministic(request)?.let { return buildPreview(skill, it) }

        val routed = router.infer(
            label = "skill-edit",
            schema = AiTasks.skillEdit,
            prompt = AiTasks.skillEditPrompt(skill.summary(), request.trim()),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                minConfidence = MIN_EDIT_CONFIDENCE,
                localOnly = localOnly,
            ),
        )
        val edit = routed.value ?: return SkillEditPreview.Rejected(
            "The change could not be understood safely",
        )
        return buildPreview(skill, edit)
    }

    /** Handles obvious time changes without spending battery or exposing text to a model. */
    private fun parseDeterministic(request: String): SkillEdit? {
        val match = TIME_PATTERN.findAll(request).lastOrNull()
            ?: KOREAN_TIME_PATTERN.findAll(request).lastOrNull()
            ?: return null
        return SkillEdit(
            field = SkillEditField.TRIGGER_TIME,
            newValue = match.value.trim(),
            meaningChanged = false,
            summary = "Change the schedule to ${match.value.trim()}",
            confidence = 1f,
        )
    }

    suspend fun apply(preview: SkillEditPreview.Ready): SkillEditApplyResult {
        val current = skillStore.get(preview.original.id)
            ?: return SkillEditApplyResult.Rejected("Automation no longer exists")
        if (current.version != preview.original.version) {
            return SkillEditApplyResult.Rejected("Automation changed while this edit was being reviewed")
        }

        val record = SkillVersionRecord(
            version = preview.updated.version,
            createdAt = time.nowMillis(),
            author = PatchAuthor.USER,
            reason = preview.summary,
            summary = preview.summary,
            changedStepIds = preview.changedStepIds,
        )
        val saved = skillStore.save(preview.updated.copy(history = current.history), record)
        scheduler.cancel(saved.id)
        scheduler.schedule(saved)
        return SkillEditApplyResult.Applied(saved)
    }

    internal fun buildPreview(skill: SemanticSkill, edit: SkillEdit): SkillEditPreview {
        val value = edit.newValue.trim()
        if (value.isEmpty()) return SkillEditPreview.Rejected("The replacement value is empty")

        val patched = when (edit.field) {
            SkillEditField.TRIGGER_TIME -> patchTime(skill, value)
            SkillEditField.TRIGGER_NOTIFICATION -> skill.copy(
                trigger = TriggerSpec.Notification(semanticCondition = value),
            )
            SkillEditField.DESTINATION -> patchDestination(skill, value)
            SkillEditField.VALUE_FIELD -> patchValueField(skill, value)
            SkillEditField.NAME -> skill.copy(name = value.take(MAX_NAME_LENGTH))
            SkillEditField.UNKNOWN -> null
        } ?: return SkillEditPreview.Rejected("That kind of change is not supported yet")

        val changedSteps = skill.steps.zip(patched.steps)
            .filter { (before, after) -> before != after }
            .map { it.second.id }
        val summary = edit.summary.ifBlank { "Update ${edit.field.name.lowercase().replace('_', ' ')} to $value" }
        return SkillEditPreview.Ready(
            original = skill,
            updated = patched.copy(version = skill.version + 1),
            summary = summary,
            meaningChanged = edit.meaningChanged,
            changedStepIds = changedSteps,
        )
    }

    private fun patchTime(skill: SemanticSkill, value: String): SemanticSkill? {
        val match = TIME_PATTERN.find(value) ?: KOREAN_TIME_PATTERN.find(value) ?: return null
        val hour = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val minute = match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        val days = (skill.trigger as? TriggerSpec.Time)?.daysOfWeek.orEmpty()
        return skill.copy(trigger = TriggerSpec.Time(hour, minute, days))
    }

    private fun patchDestination(skill: SemanticSkill, value: String): SemanticSkill {
        val destinationIndex = skill.constants.indexOfFirst { constant ->
            constant.semanticRole.equals("destination", ignoreCase = true) ||
                DESTINATION_NAMES.any { constant.name.contains(it, ignoreCase = true) }
        }.takeIf { it >= 0 }

        val constants = if (destinationIndex == null) {
            skill.constants + SkillConstant(
                name = "destination",
                value = value,
                description = "Where the result is sent",
                semanticRole = "destination",
            )
        } else {
            skill.constants.mapIndexed { index, constant ->
                if (index == destinationIndex) constant.copy(value = value, semanticRole = "destination") else constant
            }
        }
        val steps = skill.steps.map { step ->
            if (step.intent != StepIntent.SEND && step.intent != StepIntent.SHARE) return@map step
            step.copy(
                target = step.target.copy(
                    intentLabel = value,
                    description = "Send to $value",
                    locators = step.target.locators.filterNot {
                        it.kind == LocatorKind.TEXT || it.kind == LocatorKind.CONTENT_DESCRIPTION
                    },
                ),
                description = "Send to $value",
            )
        }
        return skill.copy(constants = constants, steps = steps)
    }

    private fun patchValueField(skill: SemanticSkill, value: String): SemanticSkill {
        val steps = skill.steps.map { step ->
            if (step.intent != StepIntent.READ_VALUE) return@map step
            step.copy(
                target = step.target.copy(
                    intentLabel = value,
                    description = value,
                    valueSemantics = step.target.valueSemantics?.copy(fieldName = value),
                    locators = step.target.locators.filterNot {
                        it.kind == LocatorKind.TEXT || it.kind == LocatorKind.CONTENT_DESCRIPTION
                    },
                ),
                validation = step.validation.copy(
                    valueConstraints = step.validation.valueConstraints?.copy(fieldName = value),
                ),
                description = "Read $value",
            )
        }
        return skill.copy(steps = steps)
    }

    private fun SemanticSkill.summary(): String = buildString {
        append(name).append(": ").append(goal)
        append(". Trigger: ")
        append(
            when (val value = trigger) {
                TriggerSpec.Manual -> "manual"
                is TriggerSpec.Time -> value.describe()
                is TriggerSpec.Notification -> value.describe()
            },
        )
        if (constants.isNotEmpty()) {
            append(". Constants: ")
            append(constants.joinToString { "${it.name}=${it.value}" })
        }
    }

    private companion object {
        const val MIN_EDIT_CONFIDENCE = 0.65f
        const val MAX_NAME_LENGTH = 60
        val TIME_PATTERN = Regex("(?:^|\\s)([01]?\\d|2[0-3]):([0-5]\\d)(?:\\s|$)")
        val KOREAN_TIME_PATTERN = Regex("(?:^|\\s)([01]?\\d|2[0-3])\\s*시(?:\\s*([0-5]?\\d)\\s*분)?")
        val DESTINATION_NAMES = listOf("destination", "channel", "recipient", "target", "room")
    }
}

sealed interface SkillEditPreview {
    data class Ready(
        val original: SemanticSkill,
        val updated: SemanticSkill,
        val summary: String,
        val meaningChanged: Boolean,
        val changedStepIds: List<String>,
    ) : SkillEditPreview

    data class Rejected(val reason: String) : SkillEditPreview
}

sealed interface SkillEditApplyResult {
    data class Applied(val skill: SemanticSkill) : SkillEditApplyResult
    data class Rejected(val reason: String) : SkillEditApplyResult
}
