package com.autobile.runtime.executor

import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.core.common.Ids
import com.autobile.core.common.Logx
import com.autobile.core.common.TimeSource
import com.autobile.core.data.SkillStore
import com.autobile.core.model.ActionSpec
import com.autobile.core.model.AgentTask
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.ExecutionEventType
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.OutcomeStatus
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.ResolverKind
import com.autobile.core.model.RiskVerdict
import com.autobile.core.model.RuntimeTier
import com.autobile.core.model.ScreenSnapshot
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillStep
import com.autobile.core.model.StepIntent
import com.autobile.core.model.StepResult
import com.autobile.core.model.TaskOutcome
import com.autobile.core.model.ValidationMode
import com.autobile.core.model.ValidationOutcome
import com.autobile.core.model.ValueType
import com.autobile.runtime.control.ActionResult
import com.autobile.runtime.control.ScreenActuator
import com.autobile.runtime.perception.ScreenObserver
import com.autobile.runtime.perception.ScreenshotCapture
import com.autobile.runtime.recovery.RecoveryMove
import com.autobile.runtime.recovery.SelfHealingEngine
import com.autobile.runtime.resolver.ExecutionResolver
import com.autobile.runtime.resolver.Resolution
import com.autobile.runtime.risk.RiskEngine
import com.autobile.runtime.validation.ValidationEngine
import kotlinx.coroutines.delay

/**
 * Runs a compiled skill, step by step.
 *
 * Each step follows the same cycle: observe, check risk, resolve the target, act,
 * validate, and recover if validation failed. The executor owns that loop deliberately —
 * no model is ever handed the whole run — which is what makes execution auditable,
 * interruptible, and cheap when nothing has changed.
 *
 * Progress is reported through [ExecutionObserver] as it happens rather than returned at
 * the end, because a user watching their phone operate itself needs to see what it is
 * doing while it does it.
 */
class SkillExecutor(
    private val perception: ScreenObserver,
    private val controller: ScreenActuator,
    private val resolver: ExecutionResolver,
    private val validation: ValidationEngine,
    private val healing: SelfHealingEngine,
    private val riskEngine: RiskEngine,
    private val router: AiRuntimeRouter,
    private val skillStore: SkillStore,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
    private val time: TimeSource = TimeSource.System,
) {

    suspend fun execute(
        skill: SemanticSkill,
        task: AgentTask,
        observer: ExecutionObserver,
        localOnly: Boolean = false,
        userInputs: Map<String, String> = emptyMap(),
    ): TaskOutcome {
        val context = ExecutionContext(skill, task.triggerPayload, userInputs)
        val results = mutableListOf<StepResult>()
        var cloudCalls = 0
        var deviceAiCalls = 0
        var confirmedThisRun = false

        observer.onEvent(event(task.id, ExecutionEventType.SKILL_MATCHED, message = skill.name))

        preconditionFailure(skill, localOnly)?.let { reason ->
            observer.onEvent(
                event(task.id, ExecutionEventType.PRECONDITION_CHECK, message = reason, success = false),
            )
            return TaskOutcome(
                taskId = task.id,
                status = OutcomeStatus.FAILED,
                goalValidated = false,
                completedSteps = 0,
                totalSteps = skill.steps.size,
                cloudCalls = 0,
                deviceAiCalls = 0,
                message = reason,
            )
        }

        for ((index, step) in skill.steps.withIndex()) {
            if (observer.isCancelled()) {
                return partial(task, skill, results, cloudCalls, deviceAiCalls, "Stopped", OutcomeStatus.CANCELLED)
            }

            val startedAt = time.nowMillis()
            observer.onEvent(
                event(
                    task.id,
                    ExecutionEventType.STEP_STARTED,
                    stepId = step.id,
                    stepIndex = index,
                    message = step.describeForUser(),
                ),
            )
            observer.onStepStarted(index, step)

            val snapshot = when (val observed = perception.observe(step.validation.settleMs)) {
                is PerceptionResult.Success -> observed.snapshot
                is PerceptionResult.BlockedSecureWindow -> {
                    val message = "This screen is protected and cannot be read"
                    results += failedStep(step, index, startedAt, message, ValidationMode.NONE)
                    observer.onEvent(
                        event(task.id, ExecutionEventType.STEP_FAILED, step.id, index, message, success = false),
                    )
                    return partial(task, skill, results, cloudCalls, deviceAiCalls, message, OutcomeStatus.BLOCKED)
                }

                is PerceptionResult.Unavailable -> {
                    results += failedStep(step, index, startedAt, observed.reason, ValidationMode.NONE)
                    return partial(
                        task,
                        skill,
                        results,
                        cloudCalls,
                        deviceAiCalls,
                        observed.reason,
                        OutcomeStatus.BLOCKED,
                    )
                }
            }

            val decision = riskEngine.evaluate(skill, step, snapshot.packageName, confirmedThisRun)
            observer.onEvent(
                event(
                    task.id,
                    ExecutionEventType.RISK_DECISION,
                    step.id,
                    index,
                    "${decision.verdict}: ${decision.reason}",
                ),
            )
            when (decision.verdict) {
                RiskVerdict.DENY -> {
                    results += failedStep(step, index, startedAt, decision.reason, ValidationMode.NONE)
                    return partial(
                        task,
                        skill,
                        results,
                        cloudCalls,
                        deviceAiCalls,
                        decision.reason,
                        OutcomeStatus.BLOCKED,
                    )
                }

                RiskVerdict.CONFIRM -> {
                    val approved = observer.requestConfirmation(step, decision)
                    observer.onEvent(
                        event(
                            task.id,
                            ExecutionEventType.USER_CONFIRMATION,
                            step.id,
                            index,
                            if (approved) "approved" else "declined",
                            success = approved,
                        ),
                    )
                    if (!approved) {
                        results += failedStep(step, index, startedAt, "You declined this step", ValidationMode.NONE)
                        return partial(
                            task,
                            skill,
                            results,
                            cloudCalls,
                            deviceAiCalls,
                            "You declined this step",
                            OutcomeStatus.CANCELLED,
                        )
                    }
                    confirmedThisRun = true
                }

                RiskVerdict.ALLOW -> Unit
            }

            val outcome = runStep(skill, step, index, snapshot, context, task, observer, localOnly)
            cloudCalls += outcome.cloudCalls
            deviceAiCalls += outcome.deviceAiCalls
            results += outcome.result

            if (!outcome.result.success && !step.optional) {
                val message = outcome.result.message.ifBlank { "Step failed" }
                observer.onEvent(
                    event(task.id, ExecutionEventType.STEP_FAILED, step.id, index, message, success = false),
                )
                return partial(task, skill, results, cloudCalls, deviceAiCalls, message, OutcomeStatus.PARTIAL)
            }
        }

        // Every step succeeding does not by itself mean the goal was reached, so the
        // skill's own post-conditions are checked before anything is reported as done.
        val finalSnapshot = (perception.observe() as? PerceptionResult.Success)?.snapshot
        val goalOutcome = finalSnapshot?.let {
            validation.validateGoal(skill.postconditions, it, localOnly)
        } ?: ValidationOutcome(ValidationMode.NONE, passed = skill.postconditions.isEmpty())

        observer.onEvent(
            event(
                task.id,
                ExecutionEventType.VALIDATION_RESULT,
                message = goalOutcome.reason,
                success = goalOutcome.passed,
            ),
        )

        val status = if (goalOutcome.passed) OutcomeStatus.SUCCESS else OutcomeStatus.PARTIAL
        return TaskOutcome(
            taskId = task.id,
            status = status,
            goalValidated = goalOutcome.passed,
            completedSteps = results.count { it.success },
            totalSteps = skill.steps.size,
            cloudCalls = cloudCalls,
            deviceAiCalls = deviceAiCalls,
            message = goalOutcome.reason,
            stepResults = results,
        )
    }

    private suspend fun runStep(
        skill: SemanticSkill,
        step: SkillStep,
        index: Int,
        initialSnapshot: ScreenSnapshot,
        context: ExecutionContext,
        task: AgentTask,
        observer: ExecutionObserver,
        localOnly: Boolean,
    ): StepOutcome {
        val startedAt = time.nowMillis()
        var cloudCalls = 0
        var deviceAiCalls = 0
        var snapshot = initialSnapshot
        var recovered = false

        // Steps that do not act on an element bypass resolution entirely.
        step.action.asContextFreeAction()?.let { action ->
            val actionResult = performContextFree(action, context)
            val validated = validateAfter(step, snapshot, null, localOnly, observer, task, index)
            return StepOutcome(
                result = StepResult(
                    stepId = step.id,
                    stepIndex = index,
                    intent = step.intent,
                    targetLabel = step.target.intentLabel,
                    resolver = ResolverKind.DIRECT_API,
                    tier = RuntimeTier.DETERMINISTIC,
                    success = actionResult.succeeded && validated.passed,
                    validation = validated,
                    startedAt = startedAt,
                    finishedAt = time.nowMillis(),
                    message = actionResult.describe,
                ),
                cloudCalls = 0,
                deviceAiCalls = 0,
            )
        }

        var attempt = 0
        val attemptedMoves = mutableListOf<String>()
        val navigationSteps = mutableListOf<SkillStep>()

        while (attempt <= step.fallback.maxRetries) {
            val screenshot = if (step.preferredResolver == ResolverKind.VISION || attempt > 0) {
                (perception.captureScreenshot() as? ScreenshotCapture.Success)?.bitmap
            } else {
                null
            }

            val resolution = resolver.resolve(
                target = step.target,
                snapshot = snapshot,
                screenshot = screenshot,
                allowInference = step.fallback.allowDeviceAi || step.fallback.allowCloudAi,
                allowVision = step.fallback.allowVision,
                localOnly = localOnly || !step.fallback.allowCloudAi,
            )

            if (resolution is Resolution.Found) {
                if (resolution.tier == RuntimeTier.DEVICE_AI) deviceAiCalls++
                if (resolution.usedCloud) cloudCalls++

                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.RESOLVER_SELECTED,
                        step.id,
                        index,
                        "${resolution.resolver} via ${resolution.tier.displayName} (${resolution.explanation})",
                        tier = resolution.tier,
                        resolver = resolution.resolver,
                    ),
                )
                observer.onTargetResolved(index, resolution.node)

                val readAction = step.action as? ActionSpec.ReadValue
                val extracted: String?
                val actionResult: ActionResult
                if (readAction != null) {
                    val reading = readValue(step, snapshot, screenshot, localOnly)
                    extracted = reading.value
                    if (reading.usedCloud) cloudCalls++
                    if (reading.tier == RuntimeTier.DEVICE_AI) deviceAiCalls++
                    reading.value?.let { context.putExtracted(readAction.outputVariable, it) }
                    actionResult = if (reading.value != null) {
                        ActionResult.Performed("read ${step.target.valueSemantics?.fieldName ?: "value"}")
                    } else {
                        ActionResult.Failed(reading.reason)
                    }
                } else {
                    extracted = null
                    actionResult = performOn(step, resolution, context)
                }

                observer.onEvent(
                    event(
                        task.id,
                        ExecutionEventType.ACTION_EXECUTED,
                        step.id,
                        index,
                        actionResult.describe,
                        success = actionResult.succeeded,
                    ),
                )

                if (actionResult.succeeded) {
                    snapshot = (perception.observeStable(step.validation.timeoutMs) as? PerceptionResult.Success)
                        ?.snapshot ?: snapshot
                    val validated = validateAfter(step, snapshot, extracted, localOnly, observer, task, index)
                    if (validated.passed) {
                        if (recovered) {
                            persistRepair(skill, step, resolution, navigationSteps, observer)
                        }
                        return StepOutcome(
                            result = StepResult(
                                stepId = step.id,
                                stepIndex = index,
                                intent = step.intent,
                                targetLabel = step.target.intentLabel,
                                resolver = resolution.resolver,
                                tier = resolution.tier,
                                success = true,
                                validation = validated,
                                startedAt = startedAt,
                                finishedAt = time.nowMillis(),
                                recovered = recovered,
                                message = resolution.explanation,
                                extractedValue = extracted,
                            ),
                            cloudCalls = cloudCalls,
                            deviceAiCalls = deviceAiCalls,
                        )
                    }
                }
            }

            attempt++
            if (attempt > step.fallback.maxRetries) break
            if (step.fallback.onFailure == com.autobile.core.model.FailureAction.ABORT) break

            observer.onEvent(
                event(task.id, ExecutionEventType.RECOVERY_STARTED, step.id, index, "attempt $attempt"),
            )
            val move = healing.proposeMove(
                skill = skill,
                step = step,
                snapshot = snapshot,
                attemptedMoves = attemptedMoves,
                localOnly = localOnly || !step.fallback.allowCloudAi,
            )
            val applied = applyRecoveryMove(move, snapshot, navigationSteps)
            attemptedMoves += move.reason.ifBlank { move::class.java.simpleName }
            if (move is RecoveryMove.Tap && move.usedCloud) cloudCalls++
            if (!applied) break

            recovered = true
            snapshot = (perception.observeStable() as? PerceptionResult.Success)?.snapshot ?: snapshot
            observer.onEvent(
                event(task.id, ExecutionEventType.RECOVERY_COMPLETED, step.id, index, move.reason),
            )
        }

        return StepOutcome(
            result = failedStep(
                step,
                index,
                startedAt,
                "Could not complete \"${step.describeForUser()}\"",
                step.validation.mode,
            ),
            cloudCalls = cloudCalls,
            deviceAiCalls = deviceAiCalls,
        )
    }

    private suspend fun applyRecoveryMove(
        move: RecoveryMove,
        snapshot: ScreenSnapshot,
        navigationSteps: MutableList<SkillStep>,
    ): Boolean = when (move) {
        is RecoveryMove.Tap -> {
            val result = controller.click(move.node)
            if (result.succeeded) navigationSteps += healing.navigationStepFor(move.node, move.reason)
            result.succeeded
        }

        is RecoveryMove.Scroll -> controller.scroll(
            snapshot.nodes.firstOrNull { it.scrollable },
            move.direction,
        ).succeeded

        is RecoveryMove.Back -> controller.pressBack().succeeded
        is RecoveryMove.Wait -> {
            delay(move.millis)
            true
        }

        is RecoveryMove.GiveUp -> false
    }

    /** Stores the successful repair so the next run takes the fast path. */
    private suspend fun persistRepair(
        skill: SemanticSkill,
        step: SkillStep,
        resolution: Resolution.Found,
        navigationSteps: List<SkillStep>,
        observer: ExecutionObserver,
    ) {
        val repaired = healing.repairStep(step, resolution.node)
        val summary = buildString {
            append("Relocated \"").append(step.target.intentLabel).append("\"")
            if (navigationSteps.isNotEmpty()) {
                append(" behind ").append(navigationSteps.joinToString(" > ") { it.target.intentLabel })
            }
        }
        val candidate = healing.buildPatch(skill, step, repaired, navigationSteps, summary, time.nowMillis())
        skillStore.savePatchCandidate(candidate)
        observer.onEvent(
            event(
                taskId = observer.taskId,
                type = ExecutionEventType.SKILL_PATCH_PROPOSED,
                stepId = step.id,
                message = summary,
            ),
        )
        observer.onPatchProposed(candidate.id, summary, candidate.requiresUserConfirmation)
    }

    private suspend fun validateAfter(
        step: SkillStep,
        snapshot: ScreenSnapshot,
        extracted: String?,
        localOnly: Boolean,
        observer: ExecutionObserver,
        task: AgentTask,
        index: Int,
    ): ValidationOutcome {
        val outcome = validation.validate(
            spec = step.validation,
            expected = step.expectedState,
            snapshot = snapshot,
            extractedValue = extracted,
            localOnly = localOnly,
        )
        observer.onEvent(
            event(
                task.id,
                ExecutionEventType.VALIDATION_RESULT,
                step.id,
                index,
                outcome.reason,
                success = outcome.passed,
            ),
        )
        return outcome
    }

    /** Reads a value from the screen, preferring the text already in the tree. */
    private suspend fun readValue(
        step: SkillStep,
        snapshot: ScreenSnapshot,
        screenshot: android.graphics.Bitmap?,
        localOnly: Boolean,
    ): ValueReading {
        val semantics = step.target.valueSemantics
        val fieldName = semantics?.fieldName ?: step.target.intentLabel

        // The value often sits directly beside its label in the node tree, in which case
        // no inference is needed at all.
        neighbouringValue(snapshot, fieldName, semantics?.valueType)?.let {
            return ValueReading(it, RuntimeTier.DETERMINISTIC, usedCloud = false, reason = "read from the screen")
        }

        val description = minimizer.describeScreen(snapshot)
        val routed = router.infer(
            label = "value-extraction",
            schema = AiTasks.valueExtraction,
            prompt = AiTasks.valueExtractionPrompt(fieldName, semantics?.qualifiers.orEmpty(), description),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            image = screenshot,
            requirements = InferenceRequirements(
                minConfidence = VALUE_CONFIDENCE_THRESHOLD,
                localOnly = localOnly,
                estimatedInputTokens = minimizer.estimateTokens(description),
            ),
        )
        val extracted = routed.value
        return if (extracted != null && extracted.found) {
            ValueReading(extracted.value, routed.tier, routed.usedCloud, "read by ${routed.tier.displayName}")
        } else {
            ValueReading(null, routed.tier, routed.usedCloud, "\"$fieldName\" was not found on this screen")
        }
    }

    /**
     * Finds a value positioned next to its label.
     *
     * Looks to the right of the label first, then below it, which covers the two layouts
     * essentially every form uses.
     */
    private fun neighbouringValue(snapshot: ScreenSnapshot, fieldName: String, type: ValueType?): String? {
        val label = snapshot.nodes.firstOrNull {
            it.visible && it.label().equals(fieldName, ignoreCase = true)
        } ?: return null

        val candidates = snapshot.nodes.filter { node ->
            node.nodeId != label.nodeId && node.visible && !node.label().isBlank() &&
                !node.label().equals(fieldName, ignoreCase = true)
        }

        val sameRow = candidates.filter {
            it.bounds.left >= label.bounds.left &&
                kotlin.math.abs(it.bounds.centerY - label.bounds.centerY) <= ROW_TOLERANCE_PX
        }.minByOrNull { it.bounds.left }

        val below = candidates.filter {
            it.bounds.top >= label.bounds.bottom &&
                it.bounds.top - label.bounds.bottom <= COLUMN_TOLERANCE_PX &&
                kotlin.math.abs(it.bounds.centerX - label.bounds.centerX) <= ROW_TOLERANCE_PX * 2
        }.minByOrNull { it.bounds.top }

        val value = (sameRow ?: below)?.label() ?: return null
        if (type == ValueType.NUMBER || type == ValueType.CURRENCY) {
            if (validation.parseNumber(value) == null) return null
        }
        return value
    }

    private suspend fun performOn(
        step: SkillStep,
        resolution: Resolution.Found,
        context: ExecutionContext,
    ): ActionResult = when (val action = step.action) {
        is ActionSpec.Click -> controller.click(resolution.node)
        is ActionSpec.LongPress -> controller.longPress(resolution.node, action.durationMs)
        is ActionSpec.Tap -> controller.tapRatio(action.xRatio, action.yRatio)
        is ActionSpec.Swipe -> controller.swipe(action.direction, action.distanceRatio, action.durationMs)
        is ActionSpec.Scroll -> controller.scroll(resolution.node, action.direction)
        is ActionSpec.InputText -> {
            val value = context.resolve(action.value)
            if (value == null) {
                ActionResult.Failed("Could not determine what to type")
            } else {
                controller.inputText(resolution.node, value, action.clearExisting)
            }
        }

        is ActionSpec.ReadValue, is ActionSpec.Wait, is ActionSpec.Back,
        is ActionSpec.Home, is ActionSpec.LaunchApp,
        -> ActionResult.Failed("Action does not operate on an element")
    }

    private suspend fun performContextFree(action: ActionSpec, context: ExecutionContext): ActionResult =
        when (action) {
            is ActionSpec.Back -> controller.pressBack()
            is ActionSpec.Home -> controller.pressHome()
            is ActionSpec.LaunchApp -> controller.launchApp(action.packageName, action.activity).also {
                if (it.succeeded) delay(APP_LAUNCH_SETTLE_MS)
            }

            is ActionSpec.Wait -> {
                delay(action.millis)
                ActionResult.Performed("waited ${action.millis} ms")
            }

            else -> ActionResult.Failed("Unsupported action")
        }

    /** Checks the skill's preconditions, returning the reason it cannot start. */
    private suspend fun preconditionFailure(skill: SemanticSkill, localOnly: Boolean): String? {
        if (skill.preconditions.isEmpty()) return null
        val snapshot = (perception.observe() as? PerceptionResult.Success)?.snapshot ?: return null
        for (condition in skill.preconditions) {
            val missing = condition.requiredTexts.filterNot { snapshot.containsText(it) }
            if (missing.isNotEmpty()) return "Cannot start: ${condition.description}"
        }
        return null
    }

    private fun partial(
        task: AgentTask,
        skill: SemanticSkill,
        results: List<StepResult>,
        cloudCalls: Int,
        deviceAiCalls: Int,
        message: String,
        status: OutcomeStatus,
    ) = TaskOutcome(
        taskId = task.id,
        status = status,
        goalValidated = false,
        completedSteps = results.count { it.success },
        totalSteps = skill.steps.size,
        cloudCalls = cloudCalls,
        deviceAiCalls = deviceAiCalls,
        message = message,
        stepResults = results,
    )

    private fun failedStep(
        step: SkillStep,
        index: Int,
        startedAt: Long,
        message: String,
        mode: ValidationMode,
    ) = StepResult(
        stepId = step.id,
        stepIndex = index,
        intent = step.intent,
        targetLabel = step.target.intentLabel,
        resolver = null,
        tier = RuntimeTier.DETERMINISTIC,
        success = false,
        validation = ValidationOutcome(mode, passed = false, reason = message),
        startedAt = startedAt,
        finishedAt = time.nowMillis(),
        message = message,
    )

    private fun event(
        taskId: String,
        type: ExecutionEventType,
        stepId: String? = null,
        stepIndex: Int? = null,
        message: String = "",
        success: Boolean? = null,
        tier: RuntimeTier? = null,
        resolver: ResolverKind? = null,
    ) = ExecutionEvent(
        id = Ids.event(),
        taskId = taskId,
        timestamp = time.nowMillis(),
        type = type,
        stepId = stepId,
        stepIndex = stepIndex,
        message = Logx.redact(message),
        tier = tier,
        resolver = resolver,
        success = success,
    )

    private companion object {
        const val VALUE_CONFIDENCE_THRESHOLD = 0.6f
        const val APP_LAUNCH_SETTLE_MS = 1_200L
        const val ROW_TOLERANCE_PX = 40
        const val COLUMN_TOLERANCE_PX = 160
    }
}

private data class StepOutcome(val result: StepResult, val cloudCalls: Int, val deviceAiCalls: Int)

private data class ValueReading(
    val value: String?,
    val tier: RuntimeTier,
    val usedCloud: Boolean,
    val reason: String,
)

/** Actions that need no on-screen target, and so skip resolution entirely. */
private fun ActionSpec.asContextFreeAction(): ActionSpec? = when (this) {
    is ActionSpec.Back, is ActionSpec.Home, is ActionSpec.LaunchApp, is ActionSpec.Wait -> this
    else -> null
}

/** A short phrase describing a step to a person watching it run. */
fun SkillStep.describeForUser(): String = description.ifBlank {
    val target = target.description.ifBlank { target.intentLabel }
    when (intent) {
        StepIntent.LAUNCH_APP -> "Opening $target"
        StepIntent.NAVIGATE -> "Going to $target"
        StepIntent.SELECT_ITEM -> "Selecting $target"
        StepIntent.OPEN_TARGET -> "Opening $target"
        StepIntent.READ_VALUE -> "Reading $target"
        StepIntent.ENTER_TEXT -> "Entering $target"
        StepIntent.SET_OPTION -> "Setting $target"
        StepIntent.SCROLL_TO -> "Looking for $target"
        StepIntent.CONFIRM -> "Confirming $target"
        StepIntent.SEND -> "Sending $target"
        StepIntent.SHARE -> "Sharing $target"
        StepIntent.SAVE -> "Saving $target"
        StepIntent.DELETE -> "Deleting $target"
        StepIntent.GO_BACK -> "Going back"
        StepIntent.GO_HOME -> "Going to the home screen"
        StepIntent.WAIT -> "Waiting"
    }
}
