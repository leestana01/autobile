package com.autobile.runtime.compiler

import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.ai.task.AnalysedVariable
import com.autobile.core.common.Ids
import com.autobile.core.common.TimeSource
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AutonomyLevel
import com.autobile.core.model.Condition
import com.autobile.core.model.ConditionKind
import com.autobile.core.model.DemonstrationTrace
import com.autobile.core.model.Direction
import com.autobile.core.model.EventClassification
import com.autobile.core.model.ExpectedState
import com.autobile.core.model.FallbackPolicy
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.Locator
import com.autobile.core.model.LocatorKind
import com.autobile.core.model.ObservedAction
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RiskPolicy
import com.autobile.core.model.RuntimeRequirements
import com.autobile.core.model.ScreenSemantics
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConfidence
import com.autobile.core.model.SkillConstant
import com.autobile.core.model.SkillStep
import com.autobile.core.model.SkillVariable
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.StepIntent
import com.autobile.core.model.TargetSemantics
import com.autobile.core.model.TaskComplexity
import com.autobile.core.model.TraceEvent
import com.autobile.core.model.TriggerSpec
import com.autobile.core.model.UiNode
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationSpec
import com.autobile.core.model.ValueConstraints
import com.autobile.core.model.ValueRef
import com.autobile.core.model.ValueSemantics
import com.autobile.core.model.ValueType
import com.autobile.core.model.VariableBinding
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.teach.SegmentedTrace
import com.autobile.runtime.teach.TraceSegmenter
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns a recorded demonstration into an executable skill.
 *
 * This is the step that separates Autobile from a macro recorder. A recording replays
 * what happened; a compiled skill encodes what the user *meant*, which is what allows it
 * to still work when the app has been redesigned, when the date has moved on, and when
 * the list is in a different order.
 *
 * The pipeline is: segment the trace, describe each surviving action semantically, infer
 * the overall goal, work out which recorded values were incidental, and assemble a
 * skill whose steps validate themselves.
 *
 * The result is always presented to the user for confirmation before it is saved.
 * Inferring intent from behaviour is genuinely ambiguous, and a wrong inference that is
 * never surfaced becomes an automation that quietly does the wrong thing every day.
 */
class SkillCompiler(
    private val router: AiRuntimeRouter,
    private val segmenter: TraceSegmenter,
    private val riskEngine: RiskEngine,
    private val time: TimeSource = TimeSource.System,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.systemDefault()) },
) {

    suspend fun compile(
        trace: DemonstrationTrace,
        localOnly: Boolean = false,
    ): CompilationResult {
        if (trace.events.isEmpty()) {
            return CompilationResult.Failed("Nothing was recorded")
        }

        val segmented = segmenter.segment(trace, localOnly)
        val compilable = segmented.compilable()
        if (compilable.isEmpty()) {
            return CompilationResult.Failed("No repeatable steps were found in this demonstration")
        }

        val rendered = renderSteps(compilable)
        val goal = inferGoal(rendered, localOnly)
        val analysis = analyseValues(rendered, localOnly)

        val variables = buildVariables(analysis?.variables.orEmpty())
        val constants = analysis?.constants.orEmpty().map {
            SkillConstant(name = it.name, value = it.value, description = it.why)
        }

        val steps = compilable.mapIndexedNotNull { index, event ->
            buildStep(event, index, compilable, variables)
        }
        if (steps.isEmpty()) {
            return CompilationResult.Failed("The recorded actions could not be turned into steps")
        }

        val now = time.nowMillis()
        val riskCategories = steps.flatMap { riskEngine.categorise(it) }.toSet()

        val skill = SemanticSkill(
            id = Ids.skill(),
            version = 1,
            name = goal?.name?.ifBlank { null } ?: defaultName(trace),
            goal = goal?.goal?.ifBlank { null } ?: defaultGoal(compilable),
            description = goal?.summary.orEmpty(),
            trigger = TriggerSpec.Manual,
            variables = variables,
            constants = constants,
            preconditions = buildPreconditions(compilable),
            steps = steps,
            postconditions = buildPostconditions(steps),
            riskPolicy = RiskPolicy(
                categories = riskCategories,
                requireConfirmation = riskCategories.isNotEmpty(),
                maxAutonomy = if (riskCategories.isEmpty()) {
                    AutonomyLevel.L4_EXPLICITLY_TRUSTED
                } else {
                    AutonomyLevel.L2_ASK_BEFORE_ACTION
                },
            ),
            // A newly compiled skill has never actually run. It starts needing
            // confirmation and earns autonomy from its own track record.
            autonomyLevel = AutonomyLevel.L2_ASK_BEFORE_ACTION,
            confidence = SkillConfidence(score = INITIAL_CONFIDENCE),
            runtimeRequirements = RuntimeRequirements(
                requiredPackages = compilable.map { it.packageName }.filter { it.isNotBlank() }.distinct(),
                requiresScreenshot = steps.any { it.preferredResolver == ResolverKind.VISION },
            ),
            history = listOf(
                SkillVersionRecord(
                    version = 1,
                    createdAt = now,
                    author = PatchAuthor.COMPILER,
                    reason = "Learned from a demonstration",
                ),
            ),
            createdAt = now,
            updatedAt = now,
        )

        return CompilationResult.Success(
            skill = skill,
            segmented = segmented,
            summary = goal?.summary?.ifBlank { null } ?: skill.goal,
            usedCloud = segmented.usedCloud,
            discardedSteps = segmented.discardedCount,
        )
    }

    /**
     * Builds one step from one recorded action.
     *
     * The recorded identifiers become locators for the fast path, while the label and
     * its surrounding screen become the semantic description used when those locators
     * stop matching.
     */
    private fun buildStep(
        event: TraceEvent,
        index: Int,
        all: List<TraceEvent>,
        variables: List<SkillVariable>,
    ): SkillStep? {
        val node = event.targetNode
        val action = event.action

        if (action is ObservedAction.AppOpen) {
            return SkillStep(
                id = Ids.step(),
                intent = StepIntent.LAUNCH_APP,
                target = TargetSemantics(
                    intentLabel = action.packageName.substringAfterLast('.'),
                    description = "the ${action.packageName.substringAfterLast('.')} app",
                ),
                preferredResolver = ResolverKind.DIRECT_API,
                action = ActionSpec.LaunchApp(action.packageName),
                expectedState = ExpectedState(requiredPackage = action.packageName),
                validation = ValidationSpec(mode = ValidationMode.STRUCTURAL, timeoutMs = APP_LAUNCH_TIMEOUT_MS),
                description = "Open ${action.packageName.substringAfterLast('.')}",
            )
        }

        val target = node?.let { toTargetSemantics(it, event) } ?: return null
        val intent = inferIntent(event, all, index)

        val actionSpec = when (action) {
            is ObservedAction.Click -> if (intent == StepIntent.READ_VALUE) {
                ActionSpec.ReadValue(outputVariable = target.intentLabel.toVariableName())
            } else {
                ActionSpec.Click
            }

            is ObservedAction.LongClick -> ActionSpec.LongPress()
            is ObservedAction.Select -> ActionSpec.Click
            is ObservedAction.Scroll -> ActionSpec.Scroll(Direction.DOWN)
            is ObservedAction.TextInput -> ActionSpec.InputText(
                value = valueRefFor(action.value, variables),
            )

            is ObservedAction.Back -> ActionSpec.Back
            is ObservedAction.Home -> ActionSpec.Home
            else -> return null
        }

        val readsValue = actionSpec is ActionSpec.ReadValue
        return SkillStep(
            id = Ids.step(),
            intent = intent,
            target = target,
            preferredResolver = ResolverKind.ACCESSIBILITY_NODE,
            action = actionSpec,
            expectedState = expectedStateAfter(event, all, index),
            validation = validationFor(intent, target, readsValue, event, all, index),
            fallback = FallbackPolicy(),
            description = describeStep(intent, target.description.ifBlank { target.intentLabel }),
        )
    }

    /**
     * Records everything known about the element, ordered by how well it is expected to
     * survive an app update.
     */
    private fun toTargetSemantics(node: UiNode, event: TraceEvent): TargetSemantics {
        val label = node.label()
        val locators = buildList {
            node.resourceId?.let { add(Locator(LocatorKind.RESOURCE_ID, it, node.packageName, strength = 1f)) }
            label.takeIf { it.isNotBlank() }?.let { add(Locator(LocatorKind.TEXT, it, strength = 0.7f)) }
            node.contentDescription?.takeIf { it.isNotBlank() && it != label }?.let {
                add(Locator(LocatorKind.CONTENT_DESCRIPTION, it, strength = 0.6f))
            }
            if (node.indexPath.isNotEmpty()) {
                add(Locator(LocatorKind.HIERARCHY_PATH, node.hierarchyPath(), strength = 0.3f))
            }
        }
        return TargetSemantics(
            intentLabel = label.ifBlank { node.className?.substringAfterLast('.') ?: "element" },
            description = label,
            synonyms = emptyList(),
            locators = locators,
            screen = event.before?.let {
                ScreenSemantics(
                    label = it.windowTitle.ifBlank { it.packageName },
                    packageName = it.packageName,
                )
            },
            valueSemantics = null,
        )
    }

    /**
     * Works out what an action was for from what followed it.
     *
     * An action that left the screen unchanged was almost certainly reading something; a
     * tap that opened a new window was navigation; a tap that ended the demonstration
     * was the point of the whole exercise.
     */
    private fun inferIntent(event: TraceEvent, all: List<TraceEvent>, index: Int): StepIntent {
        val label = event.targetNode?.label().orEmpty().lowercase()
        val isLast = index == all.lastIndex

        return when {
            event.action is ObservedAction.TextInput -> StepIntent.ENTER_TEXT
            event.action is ObservedAction.Scroll -> StepIntent.SCROLL_TO
            SEND_TERMS.any { label.contains(it) } -> StepIntent.SEND
            SHARE_TERMS.any { label.contains(it) } -> StepIntent.SHARE
            SAVE_TERMS.any { label.contains(it) } -> StepIntent.SAVE
            DELETE_TERMS.any { label.contains(it) } -> StepIntent.DELETE
            CONFIRM_TERMS.any { label.contains(it) } -> StepIntent.CONFIRM
            event.classification == EventClassification.OBSERVATION -> StepIntent.READ_VALUE
            event.stateTransition?.changedScreen == true -> StepIntent.NAVIGATE
            isLast -> StepIntent.CONFIRM
            else -> StepIntent.SELECT_ITEM
        }
    }

    /** Derives the post-condition from the screen the demonstration actually reached. */
    private fun expectedStateAfter(event: TraceEvent, all: List<TraceEvent>, index: Int): ExpectedState {
        val after = event.after ?: return ExpectedState()
        val next = all.getOrNull(index + 1)
        val anchorTexts = next?.targetNode?.label()?.takeIf { it.isNotBlank() }?.let { listOf(it) }.orEmpty()
        return ExpectedState(
            screen = ScreenSemantics(
                label = after.windowTitle.ifBlank { after.packageName },
                packageName = after.packageName,
            ),
            requiredPackage = after.packageName.takeIf { it.isNotBlank() },
            // The next step's target is the strongest available evidence that this step
            // landed where it was supposed to.
            requiredTexts = anchorTexts,
        )
    }

    private fun validationFor(
        intent: StepIntent,
        target: TargetSemantics,
        readsValue: Boolean,
        event: TraceEvent,
        all: List<TraceEvent>,
        index: Int,
    ): ValidationSpec {
        val isFinal = index == all.lastIndex
        return when {
            readsValue -> ValidationSpec(
                mode = ValidationMode.VALUE,
                valueConstraints = ValueConstraints(
                    fieldName = target.description.ifBlank { target.intentLabel },
                    expectedType = ValueType.TEXT,
                ),
                goalCritical = true,
            )

            intent == StepIntent.SEND || intent == StepIntent.SHARE || isFinal -> ValidationSpec(
                mode = ValidationMode.SEMANTIC,
                expectation = "the ${intent.name.lowercase().replace('_', ' ')} completed successfully",
                goalCritical = true,
            )

            else -> ValidationSpec(mode = ValidationMode.STRUCTURAL)
        }
    }

    private suspend fun inferGoal(rendered: String, localOnly: Boolean) = router.infer(
        label = "goal-inference",
        schema = AiTasks.goalInference,
        prompt = AiTasks.goalInferencePrompt(rendered),
        systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
        requirements = InferenceRequirements(
            complexity = TaskComplexity.MODERATE,
            minConfidence = GOAL_CONFIDENCE_THRESHOLD,
            localOnly = localOnly,
        ),
    ).value

    private suspend fun analyseValues(rendered: String, localOnly: Boolean) = router.infer(
        label = "variable-analysis",
        schema = AiTasks.variableAnalysis,
        prompt = AiTasks.variableAnalysisPrompt(today().toString(), rendered),
        systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
        requirements = InferenceRequirements(
            complexity = TaskComplexity.MODERATE,
            minConfidence = ANALYSIS_CONFIDENCE_THRESHOLD,
            localOnly = localOnly,
        ),
        maxOutputTokens = ANALYSIS_OUTPUT_TOKENS,
    ).value

    /**
     * Converts analysed values into bindings.
     *
     * A date the user typed becomes a relative binding rather than the literal they
     * happened to type. Without this the automation reports the same day forever.
     */
    private fun buildVariables(analysed: List<AnalysedVariable>): List<SkillVariable> = analysed.map { variable ->
        SkillVariable(
            name = variable.name.toVariableName(),
            type = if (variable.isRelativeDate) ValueType.DATE else ValueType.TEXT,
            binding = if (variable.isRelativeDate) {
                VariableBinding.RelativeDate(
                    offsetDays = variable.relativeDays,
                    pattern = detectDatePattern(variable.observedValue),
                )
            } else {
                VariableBinding.Extracted()
            },
            description = variable.meaning,
            exampleValue = variable.observedValue,
        )
    }

    /**
     * Refers typed text to a variable when the demonstration showed it was one.
     *
     * Matching on the observed value is what connects "the user typed 2026-09-10" to
     * "this field takes yesterday's date".
     */
    private fun valueRefFor(typed: String, variables: List<SkillVariable>): ValueRef {
        variables.firstOrNull { it.exampleValue == typed }?.let { return ValueRef.Variable(it.name) }
        return ValueRef.Literal(typed)
    }

    private fun detectDatePattern(value: String): String = when {
        Regex("\\d{4}-\\d{2}-\\d{2}").matches(value) -> "yyyy-MM-dd"
        Regex("\\d{4}/\\d{2}/\\d{2}").matches(value) -> "yyyy/MM/dd"
        Regex("\\d{4}\\.\\d{2}\\.\\d{2}").matches(value) -> "yyyy.MM.dd"
        Regex("\\d{2}/\\d{2}/\\d{4}").matches(value) -> "MM/dd/yyyy"
        else -> "yyyy-MM-dd"
    }

    private fun buildPreconditions(events: List<TraceEvent>): List<Condition> {
        val firstApp = events.firstOrNull { it.packageName.isNotBlank() }?.packageName ?: return emptyList()
        return listOf(
            Condition(
                description = "${firstApp.substringAfterLast('.')} is installed",
                kind = ConditionKind.STRUCTURAL,
                packageName = firstApp,
            ),
        )
    }

    /**
     * Derives the goal-level check from the last meaningful step.
     *
     * Without this, a run where every step succeeded but the final send silently failed
     * would still be reported as complete.
     */
    private fun buildPostconditions(steps: List<SkillStep>): List<Condition> {
        val final = steps.lastOrNull { it.validation.goalCritical } ?: return emptyList()
        return listOf(
            Condition(
                description = final.validation.expectation.ifBlank {
                    "${final.describeShort()} completed"
                },
                kind = ConditionKind.SEMANTIC,
            ),
        )
    }

    private fun renderSteps(events: List<TraceEvent>): String =
        events.mapIndexed { index, event ->
            val app = event.packageName.substringAfterLast('.')
            val label = com.autobile.core.common.Logx.redact(
                event.targetNode?.label().orEmpty().ifBlank { event.windowContext },
            )
            val typed = event.inputValue?.let { " typed \"${com.autobile.core.common.Logx.redact(it)}\"" }.orEmpty()
            val screen = event.after?.windowTitle?.takeIf { it.isNotBlank() }?.let { " → $it" }.orEmpty()
            "$index. [$app] ${event.action.actionVerb()} \"$label\"$typed$screen"
        }.joinToString("\n")

    private fun defaultName(trace: DemonstrationTrace): String =
        trace.label.ifBlank {
            trace.packages.firstOrNull()?.substringAfterLast('.')?.replaceFirstChar { it.uppercase() }
                ?: "New automation"
        }

    private fun defaultGoal(events: List<TraceEvent>): String {
        val apps = events.map { it.packageName.substringAfterLast('.') }.filter { it.isNotBlank() }.distinct()
        return "Repeat a ${events.size}-step task in ${apps.joinToString(" and ")}"
    }

    private fun describeStep(intent: StepIntent, target: String): String = when (intent) {
        StepIntent.LAUNCH_APP -> "Open $target"
        StepIntent.NAVIGATE -> "Go to $target"
        StepIntent.SELECT_ITEM -> "Select $target"
        StepIntent.OPEN_TARGET -> "Open $target"
        StepIntent.READ_VALUE -> "Read $target"
        StepIntent.ENTER_TEXT -> "Enter text in $target"
        StepIntent.SET_OPTION -> "Set $target"
        StepIntent.SCROLL_TO -> "Scroll to $target"
        StepIntent.CONFIRM -> "Confirm $target"
        StepIntent.SEND -> "Send using $target"
        StepIntent.SHARE -> "Share via $target"
        StepIntent.SAVE -> "Save with $target"
        StepIntent.DELETE -> "Delete using $target"
        StepIntent.GO_BACK -> "Go back"
        StepIntent.GO_HOME -> "Go to the home screen"
        StepIntent.WAIT -> "Wait"
    }

    private companion object {
        const val INITIAL_CONFIDENCE = 0.55f
        const val GOAL_CONFIDENCE_THRESHOLD = 0.4f
        const val ANALYSIS_CONFIDENCE_THRESHOLD = 0.4f
        const val ANALYSIS_OUTPUT_TOKENS = 1_024
        const val APP_LAUNCH_TIMEOUT_MS = 8_000L

        val SEND_TERMS = listOf("send", "post", "submit", "보내기", "전송", "등록")
        val SHARE_TERMS = listOf("share", "공유")
        val SAVE_TERMS = listOf("save", "저장", "download", "다운로드")
        val DELETE_TERMS = listOf("delete", "remove", "삭제")
        val CONFIRM_TERMS = listOf("confirm", "ok", "done", "확인", "완료")
    }
}

sealed interface CompilationResult {
    data class Success(
        val skill: SemanticSkill,
        val segmented: SegmentedTrace,
        /** Plain-language description shown to the user for confirmation. */
        val summary: String,
        val usedCloud: Boolean,
        val discardedSteps: Int,
    ) : CompilationResult

    data class Failed(val reason: String) : CompilationResult
}

private fun SkillStep.describeShort(): String = description.ifBlank { target.intentLabel }

private fun String.toVariableName(): String {
    val cleaned = trim().split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }
    if (cleaned.isEmpty()) return "value"
    return cleaned.first().replaceFirstChar { it.lowercase() } +
        cleaned.drop(1).joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }
}

private fun ObservedAction.actionVerb(): String = when (this) {
    is ObservedAction.Click -> "tapped"
    is ObservedAction.LongClick -> "held"
    is ObservedAction.Select -> "selected"
    is ObservedAction.TextInput -> "typed into"
    is ObservedAction.Scroll -> "scrolled"
    is ObservedAction.Back -> "went back from"
    is ObservedAction.Home -> "went home from"
    is ObservedAction.AppOpen -> "opened"
    is ObservedAction.WindowChange -> "moved to"
}
