package com.autobile.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local persistence for skills, traces, tasks and execution history.
 *
 * Everything Autobile learns about a user stays on the device; there is no server-side
 * mirror of this data.
 *
 * Each skill is stored as a single JSON document rather than a normalised set of
 * tables. Skills evolve as whole units — a self-healing patch may rewrite several
 * steps at once — so snapshotting the document makes versioning and rollback trivial,
 * at the cost of querying by step, which nothing needs to do.
 */
class AutobileDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE skills (
                id TEXT PRIMARY KEY NOT NULL,
                version INTEGER NOT NULL,
                name TEXT NOT NULL,
                goal TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                trigger_kind TEXT NOT NULL DEFAULT 'manual',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                document TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE skill_versions (
                skill_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                author TEXT NOT NULL,
                reason TEXT NOT NULL DEFAULT '',
                document TEXT NOT NULL,
                PRIMARY KEY (skill_id, version)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE skill_patch_candidates (
                id TEXT PRIMARY KEY NOT NULL,
                skill_id TEXT NOT NULL,
                base_version INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                status TEXT NOT NULL,
                summary TEXT NOT NULL DEFAULT '',
                requires_user_confirmation INTEGER NOT NULL DEFAULT 0,
                document TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE traces (
                id TEXT PRIMARY KEY NOT NULL,
                label TEXT NOT NULL DEFAULT '',
                started_at INTEGER NOT NULL,
                ended_at INTEGER NOT NULL,
                document TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE tasks (
                id TEXT PRIMARY KEY NOT NULL,
                goal TEXT NOT NULL,
                skill_id TEXT,
                state TEXT NOT NULL,
                origin TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                finished_at INTEGER,
                document TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE execution_events (
                id TEXT PRIMARY KEY NOT NULL,
                task_id TEXT NOT NULL,
                timestamp INTEGER NOT NULL,
                type TEXT NOT NULL,
                document TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE task_outcomes (
                task_id TEXT PRIMARY KEY NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                document TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE app_policies (
                package_name TEXT PRIMARY KEY NOT NULL,
                mode TEXT NOT NULL,
                category TEXT NOT NULL,
                user_set INTEGER NOT NULL DEFAULT 0,
                label TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE metric_counters (
                name TEXT PRIMARY KEY NOT NULL,
                value INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE autonomous_completions (
                task_id TEXT PRIMARY KEY NOT NULL,
                completed_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_events_task ON execution_events(task_id, timestamp)")
        db.execSQL("CREATE INDEX idx_tasks_created ON tasks(created_at DESC)")
        db.execSQL("CREATE INDEX idx_skill_versions ON skill_versions(skill_id, version DESC)")
        db.execSQL("CREATE INDEX idx_patch_skill ON skill_patch_candidates(skill_id, status)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first shipped schema, so there is nothing to migrate from
        // yet. Future revisions add their migrations here.
    }

    companion object {
        const val DATABASE_NAME = "autobile.db"
        const val DATABASE_VERSION = 1
    }
}
