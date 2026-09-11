package com.autobile.core.data

import android.content.ContentValues
import android.database.Cursor
import com.autobile.core.common.AutobileJson
import com.autobile.core.common.Ids
import com.autobile.core.common.Logx
import com.autobile.core.common.TimeSource
import com.autobile.core.model.PatchAuthor
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.SkillVersionRecord
import com.autobile.core.model.TriggerSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Skill persistence with full version history.
 *
 * Every write archives the document it replaces into `skill_versions`. Skills change
 * without the user asking — self-healing rewrites steps after an app update — so an
 * unconditional archive is what makes those changes reviewable and reversible instead
 * of silent.
 */
class SkillStore(
    private val db: AutobileDatabase,
    private val time: TimeSource = TimeSource.System,
) {
    private val changes = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)

    fun observeSkills(): Flow<List<SemanticSkill>> =
        changes.onStart { emit(Unit) }.map { listSkills() }

    suspend fun listSkills(): List<SemanticSkill> = withContext(Dispatchers.IO) {
        query("SELECT document FROM skills ORDER BY updated_at DESC")
    }

    suspend fun listEnabledSkills(): List<SemanticSkill> = withContext(Dispatchers.IO) {
        query("SELECT document FROM skills WHERE enabled = 1 ORDER BY updated_at DESC")
    }

    suspend fun get(id: String): SemanticSkill? = withContext(Dispatchers.IO) {
        query("SELECT document FROM skills WHERE id = ?", arrayOf(id)).firstOrNull()
    }

    /**
     * Inserts or replaces a skill, archiving the version it supersedes.
     *
     * @param record why this version exists. It is appended to the skill's history and
     *   shown to the user, so it should read as an explanation rather than a diff.
     */
    suspend fun save(skill: SemanticSkill, record: SkillVersionRecord? = null): SemanticSkill =
        withContext(Dispatchers.IO) {
            val now = time.nowMillis()
            val previous = query("SELECT document FROM skills WHERE id = ?", arrayOf(skill.id)).firstOrNull()
            val history = if (record != null) skill.history + record else skill.history
            val stored = skill.copy(
                createdAt = previous?.createdAt ?: skill.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = now,
                history = history,
            )
            val writable = db.writableDatabase
            writable.beginTransaction()
            try {
                if (previous != null) {
                    writable.insertWithOnConflict(
                        "skill_versions",
                        null,
                        ContentValues().apply {
                            put("skill_id", previous.id)
                            put("version", previous.version)
                            put("created_at", previous.updatedAt)
                            put("author", (previous.history.lastOrNull()?.author ?: PatchAuthor.COMPILER).name)
                            put("reason", previous.history.lastOrNull()?.reason ?: "initial")
                            put("document", AutobileJson.encodeToString(previous))
                        },
                        android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
                writable.insertWithOnConflict(
                    "skills",
                    null,
                    ContentValues().apply {
                        put("id", stored.id)
                        put("version", stored.version)
                        put("name", stored.name)
                        put("goal", stored.goal)
                        put("enabled", if (stored.enabled) 1 else 0)
                        put("trigger_kind", triggerKind(stored.trigger))
                        put("created_at", stored.createdAt)
                        put("updated_at", stored.updatedAt)
                        put("document", AutobileJson.encodeToString(stored))
                    },
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
                )
                writable.setTransactionSuccessful()
            } finally {
                writable.endTransaction()
            }
            changes.tryEmit(Unit)
            stored
        }

    suspend fun setEnabled(id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val skill = get(id) ?: return@withContext
        save(skill.copy(enabled = enabled))
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("skills", "id = ?", arrayOf(id))
        db.writableDatabase.delete("skill_versions", "skill_id = ?", arrayOf(id))
        db.writableDatabase.delete("skill_patch_candidates", "skill_id = ?", arrayOf(id))
        changes.tryEmit(Unit)
    }

    suspend fun versions(skillId: String): List<SemanticSkill> = withContext(Dispatchers.IO) {
        query(
            "SELECT document FROM skill_versions WHERE skill_id = ? ORDER BY version DESC",
            arrayOf(skillId),
        )
    }

    /** Restores an archived version as a new head version (never rewrites history). */
    suspend fun rollbackTo(skillId: String, version: Int): SemanticSkill? = withContext(Dispatchers.IO) {
        val target = query(
            "SELECT document FROM skill_versions WHERE skill_id = ? AND version = ?",
            arrayOf(skillId, version.toString()),
        ).firstOrNull() ?: return@withContext null
        val head = get(skillId) ?: return@withContext null
        val restored = target.copy(
            version = head.version + 1,
            history = head.history + SkillVersionRecord(
                version = head.version + 1,
                createdAt = time.nowMillis(),
                author = PatchAuthor.USER,
                reason = "Rolled back to v$version",
            ),
        )
        save(restored)
    }

    // -- Patch candidates -----------------------------------------------------

    suspend fun savePatchCandidate(candidate: SkillPatchCandidate) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "skill_patch_candidates",
            null,
            ContentValues().apply {
                put("id", candidate.id)
                put("skill_id", candidate.skillId)
                put("base_version", candidate.baseVersion)
                put("created_at", candidate.createdAt)
                put("status", candidate.status.name)
                put("summary", candidate.summary)
                put("requires_user_confirmation", if (candidate.requiresUserConfirmation) 1 else 0)
                put("document", AutobileJson.encodeToString(candidate.patchedSkill))
            },
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
        )
        changes.tryEmit(Unit)
    }

    suspend fun pendingPatches(): List<SkillPatchCandidate> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SkillPatchCandidate>()
        db.readableDatabase.rawQuery(
            "SELECT id, skill_id, base_version, created_at, status, summary, requires_user_confirmation, document " +
                "FROM skill_patch_candidates WHERE status = ? ORDER BY created_at DESC",
            arrayOf(PatchStatus.PENDING.name),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching {
                    out += SkillPatchCandidate(
                        id = cursor.getString(0),
                        skillId = cursor.getString(1),
                        baseVersion = cursor.getInt(2),
                        createdAt = cursor.getLong(3),
                        status = PatchStatus.valueOf(cursor.getString(4)),
                        summary = cursor.getString(5),
                        requiresUserConfirmation = cursor.getInt(6) == 1,
                        patchedSkill = AutobileJson.decodeFromString(cursor.getString(7)),
                    )
                }.onFailure { Logx.w("Skipping unreadable patch candidate", it) }
            }
        }
        out
    }

    suspend fun updatePatchStatus(id: String, status: PatchStatus) = withContext(Dispatchers.IO) {
        db.writableDatabase.update(
            "skill_patch_candidates",
            ContentValues().apply { put("status", status.name) },
            "id = ?",
            arrayOf(id),
        )
        changes.tryEmit(Unit)
    }

    private fun query(sql: String, args: Array<String>? = null): List<SemanticSkill> {
        val out = mutableListOf<SemanticSkill>()
        db.readableDatabase.rawQuery(sql, args).use { cursor: Cursor ->
            while (cursor.moveToNext()) {
                runCatching { AutobileJson.decodeFromString<SemanticSkill>(cursor.getString(0)) }
                    .onSuccess { out += it }
                    .onFailure { Logx.w("Skipping unreadable skill document", it) }
            }
        }
        return out
    }

    private fun triggerKind(trigger: TriggerSpec): String = when (trigger) {
        is TriggerSpec.Manual -> "manual"
        is TriggerSpec.Time -> "time"
        is TriggerSpec.Notification -> "notification"
    }
}

data class SkillPatchCandidate(
    val id: String = Ids.random("patch"),
    val skillId: String,
    val baseVersion: Int,
    val patchedSkill: SemanticSkill,
    val summary: String,
    val createdAt: Long,
    val status: PatchStatus = PatchStatus.PENDING,
    /**
     * True when the patch alters what the skill *means* rather than merely how it finds
     * something, in which case it must never be applied without the user agreeing.
     */
    val requiresUserConfirmation: Boolean = false,
)

enum class PatchStatus { PENDING, ACCEPTED, REJECTED }
