package com.autobile.runtime.executor

import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.RiskDecision
import com.autobile.core.model.SkillStep
import com.autobile.core.model.UiNode

/**
 * Receives progress while a skill runs.
 *
 * Execution reports as it goes rather than returning a summary at the end. A person
 * watching their phone operate itself needs to see what it is doing at the time — both
 * to trust it and to be able to stop it — and the same stream is what the history and
 * replay views are built from.
 */
interface ExecutionObserver {

    val taskId: String

    /** Appends to the execution trail. */
    suspend fun onEvent(event: ExecutionEvent)

    /** A step is about to run. */
    suspend fun onStepStarted(index: Int, step: SkillStep) = Unit

    /** The element a step will act on has been identified; used to show the indicator. */
    suspend fun onTargetResolved(index: Int, node: UiNode) = Unit

    /**
     * Asks the user to approve a risky step.
     *
     * Returning false stops the run. Implementations that cannot reach the user — a
     * background run on a locked device — must return false rather than assume consent.
     */
    suspend fun requestConfirmation(step: SkillStep, decision: RiskDecision): Boolean

    /** A repair was found and stored as a proposed new version of the skill. */
    suspend fun onPatchProposed(patchId: String, summary: String, needsConfirmation: Boolean) = Unit

    /** Polled between steps so the user can stop a run in progress. */
    fun isCancelled(): Boolean = false
}
