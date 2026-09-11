package com.autobile.core.model

import kotlinx.serialization.Serializable

/**
 * Raw counters behind the product metrics.
 *
 * Only monotonic counters are persisted; every rate is derived on read so a stored
 * ratio can never drift out of sync with the counters it was computed from.
 */
@Serializable
data class MetricCounters(
    val teachSessionsStarted: Long = 0,
    val teachSessionsCompleted: Long = 0,
    val skillsCreated: Long = 0,
    val skillsRepeated: Long = 0,
    val tasksStarted: Long = 0,
    val tasksCompleted: Long = 0,
    val tasksFailed: Long = 0,
    val autonomousTasksCompleted: Long = 0,
    val zeroCloudRuns: Long = 0,
    val cloudEscalations: Long = 0,
    val cloudEscalationsThatResolved: Long = 0,
    val aiDecisionsTotal: Long = 0,
    val aiDecisionsResolvedOnDevice: Long = 0,
    val recoveriesAttempted: Long = 0,
    val recoveriesSucceeded: Long = 0,
    val userInterventions: Long = 0,
    val falseSuccessReports: Long = 0,
    val totalTaskDurationMs: Long = 0,
)

@Serializable
data class MetricsSnapshot(
    val counters: MetricCounters = MetricCounters(),
    val weeklyAutonomousTasksCompleted: Long = 0,
    val generatedAt: Long = 0,
) {
    private fun rate(numerator: Long, denominator: Long): Float =
        if (denominator <= 0) 0f else numerator.toFloat() / denominator

    val teachCompletionRate: Float get() = rate(counters.teachSessionsCompleted, counters.teachSessionsStarted)
    val skillRepeatRate: Float get() = rate(counters.skillsRepeated, counters.skillsCreated)
    val noCloudCompletionRate: Float get() = rate(counters.zeroCloudRuns, counters.tasksCompleted)

    /** Share of AI decisions that were resolved without leaving the device. */
    val localResolutionRate: Float get() = rate(counters.aiDecisionsResolvedOnDevice, counters.aiDecisionsTotal)

    val cloudEscalationRate: Float get() = rate(counters.cloudEscalations, counters.aiDecisionsTotal)

    /** Of the cloud calls that were made, the share that actually resolved the problem. */
    val escalationEfficiency: Float get() = rate(counters.cloudEscalationsThatResolved, counters.cloudEscalations)

    val recoverySuccessRate: Float get() = rate(counters.recoveriesSucceeded, counters.recoveriesAttempted)
    val userInterventionRate: Float get() = rate(counters.userInterventions, counters.tasksStarted)
    val falseSuccessRate: Float get() = rate(counters.falseSuccessReports, counters.tasksCompleted)
    val taskSuccessRate: Float get() = rate(counters.tasksCompleted, counters.tasksStarted)
    val averageTaskDurationMs: Long
        get() = if (counters.tasksCompleted <= 0) 0 else counters.totalTaskDurationMs / counters.tasksCompleted
}
