package com.autobile.runtime.trigger

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.autobile.core.common.Logx
import com.autobile.core.model.SemanticSkill
import com.autobile.core.model.TriggerSpec
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Schedules time-based automations.
 *
 * Each occurrence is scheduled individually and the next one is booked when the current
 * one fires, rather than using a repeating schedule. Repeating work drifts and cannot
 * express "weekdays only" without firing on the other days and discarding the result,
 * which for an automation means doing real work at the wrong time.
 */
class TriggerScheduler(
    private val context: Context,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /** Books the next occurrence of [skill]'s time trigger, replacing any existing one. */
    fun schedule(skill: SemanticSkill, from: LocalDateTime = LocalDateTime.now(zone)) {
        val trigger = skill.trigger as? TriggerSpec.Time ?: return
        if (!skill.enabled) {
            cancel(skill.id)
            return
        }

        val next = nextOccurrence(trigger, from)
        val delay = Duration.between(from, next).toMillis().coerceAtLeast(0)

        val request = OneTimeWorkRequestBuilder<TriggerWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                Data.Builder()
                    .putString(TriggerWorker.KEY_SKILL_ID, skill.id)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG_PREFIX + skill.id)
            .build()

        workManager.enqueueUniqueWork(workName(skill.id), ExistingWorkPolicy.REPLACE, request)
        Logx.i("Scheduled ${skill.name} for $next")
    }

    fun cancel(skillId: String) {
        workManager.cancelUniqueWork(workName(skillId))
    }

    fun rescheduleAll(skills: List<SemanticSkill>) {
        skills.forEach { skill ->
            if (skill.enabled && skill.trigger is TriggerSpec.Time) schedule(skill) else cancel(skill.id)
        }
    }

    /**
     * Finds the next moment the trigger should fire.
     *
     * An empty day set means every day. A time that has already passed today moves to
     * the next permitted day, so re-scheduling at any point never books an occurrence in
     * the past.
     */
    fun nextOccurrence(trigger: TriggerSpec.Time, from: LocalDateTime): LocalDateTime {
        val candidateToday = from.toLocalDate().atTime(trigger.hour, trigger.minute)
        val allowedDays = trigger.daysOfWeek.ifEmpty { ALL_DAYS }

        var candidate = if (candidateToday.isAfter(from)) candidateToday else candidateToday.plusDays(1)
        var guard = 0
        while (candidate.dayOfWeek.isoValue() !in allowedDays && guard < DAYS_IN_WEEK) {
            candidate = candidate.plusDays(1)
            guard++
        }
        return candidate
    }

    private fun workName(skillId: String) = TAG_PREFIX + skillId

    private fun DayOfWeek.isoValue(): Int = value

    private companion object {
        const val TAG_PREFIX = "autobile-trigger-"
        const val BACKOFF_MINUTES = 5L
        const val DAYS_IN_WEEK = 7
        val ALL_DAYS = setOf(1, 2, 3, 4, 5, 6, 7)
    }
}
