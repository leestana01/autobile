package com.autobile.runtime.agent

import com.autobile.ai.context.ContextMinimizer
import com.autobile.ai.router.AiRuntimeRouter
import com.autobile.ai.task.AiTasks
import com.autobile.core.common.Ids
import com.autobile.core.common.Logx
import com.autobile.core.common.TimeSource
import com.autobile.core.data.HistoryStore
import com.autobile.core.data.Metric
import com.autobile.core.data.MetricsStore
import com.autobile.core.data.SettingsStore
import com.autobile.core.data.SkillStore
import com.autobile.core.model.AgentTask
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.ExecutionEventType
import com.autobile.core.model.InferenceRequirements
import com.autobile.core.model.OutcomeStatus
import com.autobile.core.model.PerceptionResult
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillConfidence
import com.autobile.core.model.SkillStep
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TaskOutcome
import com.autobile.core.model.TaskState
import com.autobile.core.model.UiNode
import com.autobile.runtime.background.ExecutabilityEvaluator
import com.autobile.runtime.capability.CapabilityDetector
import com.autobile.runtime.executor.SkillExecutor
import com.autobile.runtime.executor.ExecutionObserver
import com.autobile.runtime.executor.describeForUser
import com.autobile.runtime.perception.PerceptionEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates a single run from request to recorded outcome.
 *
 * Everything that starts an automation — a button, a schedule, a notification, a typed
 * command — goes through here, so task creation, permission checks, history and metrics
 * all behave identically regardless of what triggered the run.
 *
 * Runs are serialised. Two automations driving the same screen at once would interfere
 * with each other in ways neither could detect, so a second request while one is running
 * is rejected rather than queued behind it.
 */
class AgentOrchestrator(
    private val executor: SkillExecutor,
    private val perception: PerceptionEngine,
    private val router: AiRuntimeRouter,
    private val skillStore: SkillStore,
    private val historyStore: HistoryStore,
    private val metrics: MetricsStore,
    private val settings: SettingsStore,
    private val executability: ExecutabilityEvaluator,
    private val capabilityDetector: CapabilityDetector,
    private val minimizer: ContextMinimizer = ContextMinimizer(),
    private val time: TimeSource = TimeSource.System,
) {

    private val runLock = Mutex()
    private val cancelled = AtomicBoolean(false)

    private val _activity = MutableStateFlow<AgentActivity>(AgentActivity.Idle)
    val activity: StateFlow<AgentActivity> = _activity.asStateFlow()

    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    /**
     * Runs a saved skill.
     *
     * @param confirmation how risky steps should be approved. A run with no way to reach
     *   the user declines rather than assuming consent, so an unattended run never
     *   performs a step the user would have been asked about.
     */
    suspend fun runSkill(
        skillId: String,
        origin: TaskOrigin,
        triggerPayload: Map<String, String> = emptyMap(),
        userInputs: Map<String, String> = emptyMap(),
        confirmation: ConfirmationMode = ConfirmationMode.AskUser(),
    ): RunResult {
        if (settings.killSwitch().engaged) {
            return RunResult.Rejected("Automation is stopped")
        }
        if (runLock.isLocked) {
            return RunResult.Rejected("Another automation is already running")
        }

        return runLock.withLock {
            val skill = skillStore.get(skillId) ?: return@withLock RunResult.Rejected("Automation not found")
            if (!skill.enabled) return@withLock RunResult.Rejected("${skill.name} is paused")

            val profile = capabilityDetector.detect()
            val state = executability.evaluate(skill, profile)
            if (!state.isRunnable) {
                val task = newTask(skill, origin, triggerPayload).copy(
                    state = TaskState.DEFERRED,
                    executability = state,
                    deferredUntil = time.nowMillis() + executability.retryDelayMillis(state),
                )
                historyStore.saveTask(task)
                historyStore.appendEvent(
                    ExecutionEvent(
                        id = Ids.event(),
                        taskId = task.id,
                        timestamp = time.nowMillis(),
                        type = ExecutionEventType.TASK_DEFERRED,
                        message = state.message,
                    ),
                )
                return@withLock RunResult.Deferred(task, state)
            }

            execute(skill, origin, triggerPayload, userInputs, confirmation)
        }
    }

    private suspend fun execute(
        skill: SemanticSkill,
        origin: TaskOrigin,
        triggerPayload: Map<String, String>,
        userInputs: Map<String, String>,
        confirmation: ConfirmationMode,
    ): RunResult {
        cancelled.set(false)
        val startedAt = time.nowMillis()
        var task = newTask(skill, origin, triggerPayload).copy(state = TaskState.RUNNING, startedAt = startedAt)
        historyStore.saveTask(task)
        metrics.increment(Metric.TASKS_STARTED)
        metrics.increment(Metric.SKILLS_REPEATED)

        _activity.value = AgentActivity.Running(
            taskId = task.id,
            skillName = skill.name,
            stepDescription = "Starting",
            stepIndex = 0,
            totalSteps = skill.steps.size,
        )

        val observer = RecordingObserver(task.id, skill, confirmation)
        val outcome = try {
            executor.execute(
                skill = skill,
                task = task,
                observer = observer,
                localOnly = !settings.privacy().cloudEnabled,
                userInputs = userInputs,
            )
        } catch (e: Throwable) {
            Logx.e("Run failed for ${skill.name}", e)
            TaskOutcome(
                taskId = task.id,
                status = OutcomeStatus.FAILED,
                goalValidated = false,
                completedSteps = 0,
                totalSteps = skill.steps.size,
                cloudCalls = 0,
                deviceAiCalls = 0,
                message = e.message ?: "The automation stopped unexpectedly",
            )
        }

        val finishedAt = time.nowMillis()
        task = task.copy(
            state = outcome.status.toTaskState(),
            finishedAt = finishedAt,
            cloudCallCount = outcome.cloudCalls,
            deviceAiCallCount = outcome.deviceAiCalls,
            recoveryCount = outcome.stepResults.count { it.recovered },
            failureReason = outcome.message.takeIf { outcome.status != OutcomeStatus.SUCCESS },
        )
        historyStore.saveTask(task)
        historyStore.saveOutcome(outcome)
        recordMetrics(task, outcome, origin, startedAt, finishedAt)
        updateConfidence(skill, outcome)

        _activity.value = AgentActivity.Idle
        _pendingConfirmation.value = null
        return RunResult.Completed(task, outcome)
    }

    /**
     * Interprets a typed or spoken instruction.
     *
     * Resolves against the saved skills first: most instructions are asking for
     * something the user has already taught, and matching there avoids both an inference
     * call and the risk of improvising a different interpretation of a familiar request.
     */
    suspend fun interpretCommand(command: String): CommandResolution {
        val skills = skillStore.listEnabledSkills()
        matchExistingSkill(command, skills)?.let { return CommandResolution.MatchedSkill(it, command) }

        val screen = (perception.observe() as? PerceptionResult.Success)?.snapshot
        val description = screen?.let { minimizer.describeScreen(it) }

        val routed = router.infer(
            label = "command-intent",
            schema = AiTasks.commandIntent,
            prompt = AiTasks.commandIntentPrompt(command, description),
            systemInstruction = AiTasks.SYSTEM_INSTRUCTION,
            requirements = InferenceRequirements(
                minConfidence = COMMAND_CONFIDENCE_THRESHOLD,
                localOnly = !settings.privacy().cloudEnabled,
            ),
        )

        val intent = routed.value ?: return CommandResolution.NotUnderstood(
            "I could not work out what to do with that",
        )

        // A goal that has no learned skill behind it cannot be executed safely, so the
        // user is offered the teaching path rather than an improvised attempt.
        val candidate = skills.firstOrNull { it.goal.similarityTo(intent.goal) >= GOAL_SIMILARITY_THRESHOLD }
        return if (candidate != null) {
            CommandResolution.MatchedSkill(candidate, intent.goal)
        } else {
            CommandResolution.NeedsTeaching(intent.goal, intent.appHint, intent.parameters)
        }
    }

    /** Approves or rejects the step currently waiting on the user. */
    fun resolveConfirmation(approved: Boolean) {
        _pendingConfirmation.value?.respond(approved)
        _pendingConfirmation.value = null
    }

    /** Stops the current run at the next step boundary. */
    fun cancelCurrentRun() {
        cancelled.set(true)
        _pendingConfirmation.value?.respond(false)
        _pendingConfirmation.value = null
    }

    /**
     * Updates a skill's learned confidence from how its run went.
     *
     * Recovering from a change is a success, but a less reliable one than running
     * untouched: the skill worked, and it also told us its recorded path is drifting.
     */
    private suspend fun updateConfidence(skill: SemanticSkill, outcome: TaskOutcome) {
        val current = skill.confidence
        val succeeded = outcome.status == OutcomeStatus.SUCCESS
        val recovered = outcome.stepResults.any { it.recovered }

        val delta = when {
            succeeded && !recovered -> CONFIDENCE_GAIN
            succeeded -> CONFIDENCE_GAIN_AFTER_RECOVERY
            outcome.status == OutcomeStatus.CANCELLED || outcome.status == OutcomeStatus.DEFERRED -> 0f
            else -> CONFIDENCE_LOSS
        }

        val updated = current.copy(
            score = (current.score + delta).coerceIn(0f, 1f),
            executionCount = current.executionCount + 1,
            successCount = current.successCount + if (succeeded) 1 else 0,
            validationFailures = current.validationFailures + if (outcome.goalValidated) 0 else 1,
            recoveryCount = current.recoveryCount + if (recovered) 1 else 0,
            uiDriftEvents = current.uiDriftEvents + if (recovered) 1 else 0,
            lastUiMatchScore = outcome.stepResults.map { it.validation.confidence }.average().toFloat()
                .takeIf { !it.isNaN() } ?: current.lastUiMatchScore,
        )
        skillStore.save(skill.copy(confidence = updated))
    }

    private suspend fun recordMetrics(
        task: AgentTask,
        outcome: TaskOutcome,
        origin: TaskOrigin,
        startedAt: Long,
        finishedAt: Long,
    ) {
        when (outcome.status) {
            OutcomeStatus.SUCCESS -> {
                metrics.increment(Metric.TASKS_COMPLETED)
                metrics.increment(Metric.TOTAL_TASK_DURATION_MS, finishedAt - startedAt)
                if (task.isZeroCloud) metrics.increment(Metric.ZERO_CLOUD_RUNS)
                if (origin == TaskOrigin.TIME_TRIGGER || origin == TaskOrigin.NOTIFICATION_TRIGGER) {
                    metrics.increment(Metric.AUTONOMOUS_TASKS_COMPLETED)
                    metrics.recordAutonomousCompletion(task.id, finishedAt)
                }
            }

            OutcomeStatus.PARTIAL, OutcomeStatus.FAILED, OutcomeStatus.BLOCKED ->
                metrics.increment(Metric.TASKS_FAILED)

            OutcomeStatus.CANCELLED -> metrics.increment(Metric.USER_INTERVENTIONS)
            OutcomeStatus.DEFERRED -> Unit
        }
        if (outcome.stepResults.any { it.recovered }) {
            metrics.increment(Metric.RECOVERIES_ATTEMPTED)
            if (outcome.status == OutcomeStatus.SUCCESS) metrics.increment(Metric.RECOVERIES_SUCCEEDED)
        }
    }

    private fun newTask(skill: SemanticSkill, origin: TaskOrigin, payload: Map<String, String>) = AgentTask(
        id = Ids.task(),
        goal = skill.goal,
        origin = origin,
        skillId = skill.id,
        skillVersion = skill.version,
        createdAt = time.nowMillis(),
        triggerPayload = payload,
    )

    /**
     * Matches a command against saved skills by name and goal.
     *
     * Deliberately literal. Fuzzy matching here would occasionally run the wrong
     * automation, and the cost of that is far higher than the cost of falling through to
     * interpretation.
     */
    private fun matchExistingSkill(command: String, skills: List<SemanticSkill>): SemanticSkill? {
        val normalised = command.trim().lowercase()
        if (normalised.isEmpty()) return null
        skills.firstOrNull { it.name.lowercase() == normalised }?.let { return it }
        val containing = skills.filter { normalised.contains(it.name.lowercase()) && it.name.length >= MIN_NAME_MATCH }
        return containing.singleOrNull()
    }

    /** Progress reporter that persists events and drives the on-screen indicator. */
    private inner class RecordingObserver(
        override val taskId: String,
        private val skill: SemanticSkill,
        private val confirmation: ConfirmationMode,
    ) : ExecutionObserver {

        override suspend fun onEvent(event: ExecutionEvent) {
            historyStore.appendEvent(event)
            if (event.type == ExecutionEventType.AI_RUNTIME_SELECTED) {
                metrics.increment(Metric.AI_DECISIONS_TOTAL)
                if (event.tier?.isLocal == true) metrics.increment(Metric.AI_DECISIONS_ON_DEVICE)
                if (event.tier?.isCloud == true) metrics.increment(Metric.CLOUD_ESCALATIONS)
            }
        }

        override suspend fun onStepStarted(index: Int, step: SkillStep) {
            _activity.value = AgentActivity.Running(
                taskId = taskId,
                skillName = skill.name,
                stepDescription = step.describeForUser(),
                stepIndex = index,
                totalSteps = skill.steps.size,
            )
        }

        override suspend fun onTargetResolved(index: Int, node: UiNode) {
            val current = _activity.value
            if (current is AgentActivity.Running) {
                _activity.value = current.copy(touchTarget = node.bounds)
            }
        }

        override suspend fun requestConfirmation(step: SkillStep, decision: RiskDecision): Boolean {
            val mode = confirmation as? ConfirmationMode.AskUser ?: return false
            metrics.increment(Metric.USER_INTERVENTIONS)
            val pending = PendingConfirmation(step, decision)
            _pendingConfirmation.value = pending
            // A prompt nobody answers is treated as a refusal: an automation must never
            // proceed with a risky step because the user happened not to be looking.
            val approved = withTimeoutOrNull(mode.timeoutMs) { pending.await() } ?: false
            _pendingConfirmation.value = null
            return approved
        }

        override suspend fun onPatchProposed(patchId: String, summary: String, needsConfirmation: Boolean) {
            _activity.value = (_activity.value as? AgentActivity.Running)?.copy(
                repairNote = summary,
            ) ?: _activity.value
        }

        override fun isCancelled(): Boolean = cancelled.get() || settings.killSwitch().engaged
    }

    private companion object {
        const val CONFIDENCE_GAIN = 0.08f
        const val CONFIDENCE_GAIN_AFTER_RECOVERY = 0.02f
        const val CONFIDENCE_LOSS = -0.15f
        const val COMMAND_CONFIDENCE_THRESHOLD = 0.5f
        const val GOAL_SIMILARITY_THRESHOLD = 0.55f
        const val MIN_NAME_MATCH = 4
    }
}

/** What the agent is doing, for the status banner and the touch indicator. */
sealed interface AgentActivity {
    data object Idle : AgentActivity

    data class Running(
        val taskId: String,
        val skillName: String,
        val stepDescription: String,
        val stepIndex: Int,
        val totalSteps: Int,
        val touchTarget: com.autobile.core.model.Bounds? = null,
        val repairNote: String? = null,
    ) : AgentActivity
}

/**
 * A step waiting for the user's decision.
 *
 * Published as state so any surface — the app, the overlay, a notification — can present
 * it, and completed exactly once by whichever one the user actually responds on.
 */
class PendingConfirmation(
    val step: SkillStep,
    val decision: RiskDecision,
) {
    private val answer = CompletableDeferred<Boolean>()

    internal suspend fun await(): Boolean = answer.await()

    internal fun respond(approved: Boolean) {
        answer.complete(approved)
    }
}

/** How risky steps are approved during a run. */
sealed interface ConfirmationMode {
    /**
     * Prompt the user and wait.
     *
     * @param timeoutMs how long to wait before treating silence as a refusal.
     */
    data class AskUser(val timeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS) : ConfirmationMode

    /** Refuse every risky step without prompting, for runs with no user present. */
    data object AutoDecline : ConfirmationMode
}

const val DEFAULT_CONFIRMATION_TIMEOUT_MS: Long = 60_000L

sealed interface RunResult {
    data class Completed(val task: AgentTask, val outcome: TaskOutcome) : RunResult
    data class Deferred(val task: AgentTask, val state: com.autobile.core.model.ExecutabilityState) : RunResult
    data class Rejected(val reason: String) : RunResult
}

sealed interface CommandResolution {
    data class MatchedSkill(val skill: SemanticSkill, val goal: String) : CommandResolution
    data class NeedsTeaching(
        val goal: String,
        val appHint: String,
        val parameters: Map<String, String>,
    ) : CommandResolution

    data class NotUnderstood(val reason: String) : CommandResolution
}

private fun OutcomeStatus.toTaskState(): TaskState = when (this) {
    OutcomeStatus.SUCCESS -> TaskState.COMPLETED
    OutcomeStatus.PARTIAL, OutcomeStatus.FAILED -> TaskState.FAILED
    OutcomeStatus.DEFERRED -> TaskState.DEFERRED
    OutcomeStatus.CANCELLED -> TaskState.CANCELLED
    OutcomeStatus.BLOCKED -> TaskState.BLOCKED
}

/**
 * Word-overlap similarity between two goal descriptions.
 *
 * Intentionally simple: the result only decides whether to offer an existing skill or to
 * suggest teaching a new one, and both outcomes are presented to the user rather than
 * acted on silently.
 */
private fun String.similarityTo(other: String): Float {
    val a = lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    val b = other.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
    if (a.isEmpty() || b.isEmpty()) return 0f
    return a.intersect(b).size.toFloat() / maxOf(a.size, b.size)
}
