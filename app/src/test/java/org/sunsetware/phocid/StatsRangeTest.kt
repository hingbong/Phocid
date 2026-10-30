package org.sunsetware.phocid

import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.sunsetware.phocid.stats.domain.StatsRange

class StatsRangeTest {
    private val today = LocalDate.of(2026, 6, 15)

    @Test
    fun calendarMonthStartsOnFirstDay() {
        assertThat(StatsRange.CALENDAR_MONTH.startDate(today)).isEqualTo(LocalDate.of(2026, 6, 1))
    }

    @Test
    fun calendarYearStartsOnJanuaryFirst() {
        assertThat(StatsRange.CALENDAR_YEAR.startDate(today)).isEqualTo(LocalDate.of(2026, 1, 1))
    }

    @Test
    fun calendarYearHandlesLeapDay() {
        assertThat(StatsRange.CALENDAR_YEAR.startDate(LocalDate.of(2024, 2, 29)))
            .isEqualTo(LocalDate.of(2024, 1, 1))
    }

    @Test
    fun dayRangesIncludeToday() {
        assertThat(StatsRange.DAYS_7.startDate(today)).isEqualTo(LocalDate.of(2026, 6, 9))
        assertThat(StatsRange.DAYS_30.startDate(today)).isEqualTo(LocalDate.of(2026, 5, 17))
    }

    @Test
    fun allTimeHasNoStartDate() {
        assertThat(StatsRange.ALL.startDate(today)).isNull()
    }
}
