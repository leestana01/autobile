package com.autobile.runtime.trigger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autobile.core.common.Logx
import com.autobile.core.model.TaskOrigin
import com.autobile.core.model.TriggerSpec
import com.autobile.runtime.AutobileRuntime
import com.autobile.runtime.agent.ConfirmationMode
import com.autobile.runtime.agent.RunResult

/**
 * Runs a scheduled automation and books its next occurrence.
 *
 * Rescheduling happens in a `finally` block so a failed run still leaves the schedule
 * intact: one bad morning must not silently end a daily automation.
 *
 * Risky steps auto-decline here. A scheduled run has no user attached to it, and a
 * confirmation prompt nobody sees must never be taken for agreement.
 */
class TriggerWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val skillId = inputData.getString(KEY_SKILL_ID) ?: return Result.failure()
        val services = AutobileRuntime.services ?: return Result.retry()

        val skill = services.skillStore.get(skillId)
        if (skill == null) {
            services.triggerScheduler.cancel(skillId)
            return Result.success()
        }

        return try {
            when (val result = services.orchestrator.runSkill(
                skillId = skillId,
                origin = TaskOrigin.TIME_TRIGGER,
                confirmation = ConfirmationMode.AutoDecline,
            )) {
                is RunResult.Completed -> Result.success()

                // Not runnable yet is not a failure. Retrying keeps the automation alive
                // until the blocking condition clears, without reporting an error.
                is RunResult.Deferred -> Result.retry()

                is RunResult.Rejected -> {
                    Logx.i("Scheduled run of ${skill.name} skipped: ${result.reason}")
                    Result.success()
                }
            }
        } catch (e: Throwable) {
            Logx.e("Scheduled run of ${skill.name} failed", e)
            Result.failure()
        } finally {
            if (skill.enabled && skill.trigger is TriggerSpec.Time) {
                services.triggerScheduler.schedule(skill)
            }
        }
    }

    companion object {
        const val KEY_SKILL_ID = "skill_id"
    }
}
