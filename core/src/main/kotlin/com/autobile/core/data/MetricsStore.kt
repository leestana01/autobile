package com.autobile.core.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.autobile.core.model.MetricCounters
import com.autobile.core.model.MetricsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/** Persistent counters behind the product metrics surfaced in the app. */
class MetricsStore(private val db: AutobileDatabase) {

    private val changes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    fun observe(): Flow<MetricsSnapshot> = changes.onStart { emit(Unit) }.map { snapshot() }

    suspend fun increment(metric: Metric, delta: Long = 1) = withContext(Dispatchers.IO) {
        db.writableDatabase.execSQL(
            "INSERT INTO metric_counters(name, value) VALUES(?, ?) " +
                "ON CONFLICT(name) DO UPDATE SET value = value + ?",
            arrayOf<Any>(metric.name, delta, delta),
        )
        changes.tryEmit(Unit)
    }

    /**
     * Records a task that completed without user intervention.
     *
     * Stored per task rather than as a counter so the rolling weekly figure can be
     * computed over a real time window.
     */
    suspend fun recordAutonomousCompletion(taskId: String, completedAt: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "autonomous_completions",
            null,
            ContentValues().apply {
                put("task_id", taskId)
                put("completed_at", completedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.tryEmit(Unit)
    }

    suspend fun snapshot(now: Long = System.currentTimeMillis()): MetricsSnapshot = withContext(Dispatchers.IO) {
        val values = mutableMapOf<String, Long>()
        db.readableDatabase.rawQuery("SELECT name, value FROM metric_counters", null).use { cursor ->
            while (cursor.moveToNext()) values[cursor.getString(0)] = cursor.getLong(1)
        }
        fun v(metric: Metric) = values[metric.name] ?: 0L

        val weekAgo = now - 7L * 24 * 60 * 60 * 1000
        val weekly = db.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM autonomous_completions WHERE completed_at >= ?",
            arrayOf(weekAgo.toString()),
        ).use { if (it.moveToFirst()) it.getLong(0) else 0L }

        MetricsSnapshot(
            counters = MetricCounters(
                teachSessionsStarted = v(Metric.TEACH_SESSIONS_STARTED),
                teachSessionsCompleted = v(Metric.TEACH_SESSIONS_COMPLETED),
                skillsCreated = v(Metric.SKILLS_CREATED),
                skillsRepeated = v(Metric.SKILLS_REPEATED),
                tasksStarted = v(Metric.TASKS_STARTED),
                tasksCompleted = v(Metric.TASKS_COMPLETED),
                tasksFailed = v(Metric.TASKS_FAILED),
                autonomousTasksCompleted = v(Metric.AUTONOMOUS_TASKS_COMPLETED),
                zeroCloudRuns = v(Metric.ZERO_CLOUD_RUNS),
                cloudEscalations = v(Metric.CLOUD_ESCALATIONS),
                cloudEscalationsThatResolved = v(Metric.CLOUD_ESCALATIONS_RESOLVED),
                aiDecisionsTotal = v(Metric.AI_DECISIONS_TOTAL),
                aiDecisionsResolvedOnDevice = v(Metric.AI_DECISIONS_ON_DEVICE),
                recoveriesAttempted = v(Metric.RECOVERIES_ATTEMPTED),
                recoveriesSucceeded = v(Metric.RECOVERIES_SUCCEEDED),
                userInterventions = v(Metric.USER_INTERVENTIONS),
                falseSuccessReports = v(Metric.FALSE_SUCCESS_REPORTS),
                totalTaskDurationMs = v(Metric.TOTAL_TASK_DURATION_MS),
            ),
            weeklyAutonomousTasksCompleted = weekly,
            generatedAt = now,
        )
    }

    suspend fun reset() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("metric_counters", null, null)
        db.writableDatabase.delete("autonomous_completions", null, null)
        changes.tryEmit(Unit)
    }
}

enum class Metric {
    TEACH_SESSIONS_STARTED,
    TEACH_SESSIONS_COMPLETED,
    SKILLS_CREATED,
    SKILLS_REPEATED,
    TASKS_STARTED,
    TASKS_COMPLETED,
    TASKS_FAILED,
    AUTONOMOUS_TASKS_COMPLETED,
    ZERO_CLOUD_RUNS,
    CLOUD_ESCALATIONS,
    CLOUD_ESCALATIONS_RESOLVED,
    AI_DECISIONS_TOTAL,
    AI_DECISIONS_ON_DEVICE,
    RECOVERIES_ATTEMPTED,
    RECOVERIES_SUCCEEDED,
    USER_INTERVENTIONS,
    FALSE_SUCCESS_REPORTS,
    TOTAL_TASK_DURATION_MS,
}
