package com.autobile.runtime.trigger

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.autobile.core.model.TriggerSpec
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDateTime

/**
 * Occurrence calculation decides when an automation actually runs, and an off-by-one day
 * here means a daily report arrives on the wrong morning.
 */
@RunWith(RobolectricTestRunner::class)
class TriggerSchedulerTest {

    private lateinit var scheduler: TriggerScheduler

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        scheduler = TriggerScheduler(context)
    }

    @Test
    fun `a time later today schedules for today`() {
        val next = scheduler.nextOccurrence(
            TriggerSpec.Time(hour = 9, minute = 0),
            from = LocalDateTime.of(2026, 9, 11, 7, 30),
        )
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 9, 11, 9, 0))
    }

    @Test
    fun `a time already past today schedules for tomorrow`() {
        val next = scheduler.nextOccurrence(
            TriggerSpec.Time(hour = 9, minute = 0),
            from = LocalDateTime.of(2026, 9, 11, 10, 0),
        )
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 9, 12, 9, 0))
    }

    @Test
    fun `the exact scheduled minute is treated as past to avoid an immediate refire`() {
        val next = scheduler.nextOccurrence(
            TriggerSpec.Time(hour = 9, minute = 0),
            from = LocalDateTime.of(2026, 9, 11, 9, 0),
        )
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 9, 12, 9, 0))
    }

    @Test
    fun `a weekday schedule skips the weekend`() {
        // 2026-09-11 is a Friday; the next weekday occurrence is the following Monday.
        val next = scheduler.nextOccurrence(
            TriggerSpec.Time(hour = 9, minute = 0, daysOfWeek = setOf(1, 2, 3, 4, 5)),
            from = LocalDateTime.of(2026, 9, 11, 10, 0),
        )
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 9, 14, 9, 0))
    }

    @Test
    fun `a single day schedule advances a full week when that day has passed`() {
        val next = scheduler.nextOccurrence(
            TriggerSpec.Time(hour = 9, minute = 0, daysOfWeek = setOf(5)),
            from = LocalDateTime.of(2026, 9, 11, 10, 0),
        )
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 9, 18, 9, 0))
    }

    @Test
    fun `an empty day set means every day`() {
        val next = scheduler.nextOccurrence(
            TriggerSpec.Time(hour = 23, minute = 59),
            from = LocalDateTime.of(2026, 9, 11, 23, 58),
        )
        assertThat(next).isEqualTo(LocalDateTime.of(2026, 9, 11, 23, 59))
    }

    @Test
    fun `trigger descriptions read as plain english`() {
        assertThat(TriggerSpec.Time(9, 0).describe()).isEqualTo("Every day 09:00")
        assertThat(TriggerSpec.Time(8, 30, setOf(1, 2, 3, 4, 5)).describe())
            .isEqualTo("Mon,Tue,Wed,Thu,Fri 08:30")
    }
}
