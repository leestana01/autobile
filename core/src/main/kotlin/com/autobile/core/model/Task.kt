package com.autobile.core.model

import kotlinx.serialization.Serializable

/**
 * One goal the agent is trying to satisfy right now.
 *
 * A task may be served by an existing [SemanticSkill], or planned ad hoc from a
 * natural-language command. The per-tier call counters exist because keeping
 * repeat runs off the cloud is a product goal, and a goal that is not measured
 * silently erodes.
 */
@Serializable
data class AgentTask(
    val id: String,
    val goal: String,
    val origin: TaskOrigin,
    val skillId: String? = null,
    val skillVersion: Int? = null,
    val state: TaskState = TaskState.CREATED,
    val executability: ExecutabilityState = ExecutabilityState.EXECUTABLE,
    val createdAt: Long = 0L,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val failureReason: String? = null,
    val deferredUntil: Long? = null,
    val cloudCallCount: Int = 0,
    val deviceAiCallCount: Int = 0,
    val deterministicStepCount: Int = 0,
    val recoveryCount: Int = 0,
    val triggerPayload: Map<String, String> = emptyMap(),
) {
    val durationMs: Long?
        get() = if (startedAt != null && finishedAt != null) finishedAt - startedAt else null

    val isTerminal: Boolean
        get() = state == TaskState.COMPLETED || state == TaskState.FAILED ||
            state == TaskState.CANCELLED || state == TaskState.BLOCKED

    /** True when the whole run completed without a single cloud call. */
    val isZeroCloud: Boolean get() = cloudCallCount == 0
}

enum class TaskOrigin {
    MANUAL,
    NATURAL_LANGUAGE_COMMAND,
    TIME_TRIGGER,
    NOTIFICATION_TRIGGER,
    REPLAY,
}

enum class TaskState {
    CREATED,
    PLANNING,
    AWAITING_CONFIRMATION,
    RUNNING,
    /** Reached a step needing reasoning that no currently available runtime can perform. */
    WAITING_FOR_REASONING,
    WAITING_FOR_USER,
    /** Not runnable right now. Distinct from [FAILED]: the task will be retried. */
    DEFERRED,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED,
}

/**
 * Why a task can or cannot run at this moment.
 *
 * Android restricts what an app may start from the background, and a locked device
 * blocks whole classes of interaction. Those limits are modelled explicitly instead of
 * being collapsed into a generic failure.
 */
enum class ExecutabilityState {
    EXECUTABLE,
    DEVICE_LOCKED,
    USER_UNLOCK_REQUIRED,
    USER_INTERACTION_REQUIRED,
    OS_BLOCKED,
    APP_BLOCKED;

    val isRunnable: Boolean get() = this == EXECUTABLE

    val message: String
        get() = when (this) {
            EXECUTABLE -> "Ready to run"
            DEVICE_LOCKED -> "Device is locked"
            USER_UNLOCK_REQUIRED -> "Unlock required to continue"
            USER_INTERACTION_REQUIRED -> "Needs you to confirm something"
            OS_BLOCKED -> "Android blocked background start"
            APP_BLOCKED -> "Blocked by your app policy"
        }
}

/**
 * One entry in a task's execution trail.
 *
 * The trail is what makes an autonomous run auditable after the fact, so every
 * meaningful decision — resolver chosen, runtime tier chosen, validation verdict,
 * recovery attempt — emits one of these.
 */
@Serializable
data class ExecutionEvent(
    val id: String,
    val taskId: String,
    val timestamp: Long,
    val type: ExecutionEventType,
    val stepId: String? = null,
    val stepIndex: Int? = null,
    val message: String = "",
    val tier: RuntimeTier? = null,
    val resolver: ResolverKind? = null,
    val success: Boolean? = null,
    val detail: Map<String, String> = emptyMap(),
)

enum class ExecutionEventType {
    TASK_CREATED,
    SKILL_MATCHED,
    PRECONDITION_CHECK,
    STEP_STARTED,
    RESOLVER_SELECTED,
    AI_RUNTIME_SELECTED,
    ACTION_EXECUTED,
    VALIDATION_RESULT,
    RECOVERY_STARTED,
    RECOVERY_COMPLETED,
    RISK_DECISION,
    USER_CONFIRMATION,
    SKILL_PATCH_PROPOSED,
    TASK_DEFERRED,
    TASK_COMPLETED,
    TASK_FAILED,
    TASK_CANCELLED,
}

/** Per-step outcome, retained so a completed run can be replayed step by step. */
@Serializable
data class StepResult(
    val stepId: String,
    val stepIndex: Int,
    val intent: StepIntent,
    val targetLabel: String,
    val resolver: ResolverKind?,
    val tier: RuntimeTier,
    val success: Boolean,
    val validation: ValidationOutcome,
    val startedAt: Long,
    val finishedAt: Long,
    val recovered: Boolean = false,
    val message: String = "",
    val extractedValue: String? = null,
)

@Serializable
data class ValidationOutcome(
    val mode: ValidationMode,
    val passed: Boolean,
    val reason: String = "",
    val observed: String = "",
    val confidence: Float = 0f,
)

/**
 * The final verdict for a task.
 *
 * [OutcomeStatus.PARTIAL] is a first-class result rather than a rounded-up success:
 * telling a user an automation finished when it did not is the single most damaging
 * failure this system can produce.
 */
@Serializable
data class TaskOutcome(
    val taskId: String,
    val status: OutcomeStatus,
    val goalValidated: Boolean,
    val completedSteps: Int,
    val totalSteps: Int,
    val cloudCalls: Int,
    val deviceAiCalls: Int,
    val message: String = "",
    val stepResults: List<StepResult> = emptyList(),
)

enum class OutcomeStatus { SUCCESS, PARTIAL, FAILED, DEFERRED, CANCELLED, BLOCKED }
