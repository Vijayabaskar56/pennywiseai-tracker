package com.pennywiseai.tracker.domain.sharedexpenses

import java.time.LocalDate
import java.time.YearMonth

/** Due dates of a recurring Shared expense, as the server's Cron computes them. */
object RecurringDates {

    /**
     * The due date one [interval] after [date]. MONTHLY and YEARLY land on
     * [anchorDay] clamped to the month's last day: anchor 31 gives 30 November,
     * then 31 December; anchor 29 in February gives the 28th outside leap years.
     */
    fun next(date: LocalDate, interval: RecurringInterval, anchorDay: Int): LocalDate = when (interval) {
        RecurringInterval.WEEKLY -> date.plusWeeks(1)
        RecurringInterval.MONTHLY -> onAnchor(YearMonth.from(date).plusMonths(1), anchorDay)
        RecurringInterval.YEARLY -> onAnchor(YearMonth.from(date).plusYears(1), anchorDay)
    }

    private fun onAnchor(month: YearMonth, anchorDay: Int): LocalDate =
        month.atDay(anchorDay.coerceIn(1, month.lengthOfMonth()))
}
