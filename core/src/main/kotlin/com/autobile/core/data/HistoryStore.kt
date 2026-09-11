package com.autobile.core.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.autobile.core.common.AutobileJson
import com.autobile.core.common.Logx
import com.autobile.core.model.AgentTask
import com.autobile.core.model.ExecutionEvent
import com.autobile.core.model.TaskOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Execution history, and the data source for replaying a past run.
 *
 * Callers redact event payloads before handing them over; this store persists exactly
 * what it is given and never widens it.
 */
class HistoryStore(private val db: AutobileDatabase) {

    private val changes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    fun observeTasks(limit: Int = 100): Flow<List<AgentTask>> =
        changes.onStart { emit(Unit) }.map { recentTasks(limit) }

    suspend fun saveTask(task: AgentTask) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "tasks",
            null,
            ContentValues().apply {
                put("id", task.id)
                put("goal", task.goal)
                put("skill_id", task.skillId)
                put("state", task.state.name)
                put("origin", task.origin.name)
                put("created_at", task.createdAt)
                put("finished_at", task.finishedAt)
                put("document", AutobileJson.encodeToString(task))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.tryEmit(Unit)
    }

    suspend fun getTask(id: String): AgentTask? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT document FROM tasks WHERE id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) decode<AgentTask>(it.getString(0)) else null
        }
    }

    suspend fun recentTasks(limit: Int = 100): List<AgentTask> = withContext(Dispatchers.IO) {
        val out = mutableListOf<AgentTask>()
        db.readableDatabase.rawQuery(
            "SELECT document FROM tasks ORDER BY created_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) decode<AgentTask>(cursor.getString(0))?.let(out::add)
        }
        out
    }

    suspend fun appendEvent(event: ExecutionEvent) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "execution_events",
            null,
            ContentValues().apply {
                put("id", event.id)
                put("task_id", event.taskId)
                put("timestamp", event.timestamp)
                put("type", event.type.name)
                put("document", AutobileJson.encodeToString(event))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.tryEmit(Unit)
    }

    suspend fun events(taskId: String): List<ExecutionEvent> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ExecutionEvent>()
        db.readableDatabase.rawQuery(
            "SELECT document FROM execution_events WHERE task_id = ? ORDER BY timestamp ASC",
            arrayOf(taskId),
        ).use { cursor ->
            while (cursor.moveToNext()) decode<ExecutionEvent>(cursor.getString(0))?.let(out::add)
        }
        out
    }

    suspend fun saveOutcome(outcome: TaskOutcome) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "task_outcomes",
            null,
            ContentValues().apply {
                put("task_id", outcome.taskId)
                put("status", outcome.status.name)
                put("created_at", System.currentTimeMillis())
                put("document", AutobileJson.encodeToString(outcome))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.tryEmit(Unit)
    }

    suspend fun outcome(taskId: String): TaskOutcome? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery(
            "SELECT document FROM task_outcomes WHERE task_id = ?",
            arrayOf(taskId),
        ).use { if (it.moveToFirst()) decode<TaskOutcome>(it.getString(0)) else null }
    }

    suspend fun successRateFor(skillId: String, window: Int = 20): Float = withContext(Dispatchers.IO) {
        val tasks = mutableListOf<AgentTask>()
        db.readableDatabase.rawQuery(
            "SELECT document FROM tasks WHERE skill_id = ? ORDER BY created_at DESC LIMIT ?",
            arrayOf(skillId, window.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) decode<AgentTask>(cursor.getString(0))?.let(tasks::add)
        }
        if (tasks.isEmpty()) return@withContext 0f
        tasks.count { it.state == com.autobile.core.model.TaskState.COMPLETED }.toFloat() / tasks.size
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("execution_events", null, null)
        db.writableDatabase.delete("task_outcomes", null, null)
        db.writableDatabase.delete("tasks", null, null)
        changes.tryEmit(Unit)
    }

    private inline fun <reified T> decode(json: String): T? =
        runCatching { AutobileJson.decodeFromString<T>(json) }
            .onFailure { Logx.w("Skipping unreadable history row", it) }
            .getOrNull()
}
