package com.autobile.core.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.autobile.core.common.AutobileJson
import com.autobile.core.common.Logx
import com.autobile.core.model.DemonstrationTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Demonstration traces.
 *
 * Traces hold raw screen content captured while the user was demonstrating, which
 * makes them the most sensitive data the app stores. They are kept only while they are
 * still useful for compilation and debugging, and [prune] discards the rest.
 */
class TraceStore(private val db: AutobileDatabase) {

    suspend fun save(trace: DemonstrationTrace) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict(
            "traces",
            null,
            ContentValues().apply {
                put("id", trace.id)
                put("label", trace.label)
                put("started_at", trace.startedAt)
                put("ended_at", trace.endedAt)
                put("document", AutobileJson.encodeToString(trace))
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    suspend fun get(id: String): DemonstrationTrace? = withContext(Dispatchers.IO) {
        db.readableDatabase.rawQuery("SELECT document FROM traces WHERE id = ?", arrayOf(id)).use {
            if (!it.moveToFirst()) return@use null
            runCatching { AutobileJson.decodeFromString<DemonstrationTrace>(it.getString(0)) }
                .onFailure { e -> Logx.w("Unreadable trace", e) }
                .getOrNull()
        }
    }

    suspend fun list(limit: Int = 50): List<DemonstrationTrace> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DemonstrationTrace>()
        db.readableDatabase.rawQuery(
            "SELECT document FROM traces ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { AutobileJson.decodeFromString<DemonstrationTrace>(cursor.getString(0)) }
                    .onSuccess { out += it }
            }
        }
        out
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("traces", "id = ?", arrayOf(id))
        Unit
    }

    /** Drops traces started before [olderThanMillis]. Screen content is not kept indefinitely. */
    suspend fun prune(olderThanMillis: Long) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("traces", "started_at < ?", arrayOf(olderThanMillis.toString()))
        Unit
    }
}
