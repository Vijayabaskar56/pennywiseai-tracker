package com.pennywiseai.tracker.domain.sharedexpenses

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class RecurringDatesTest {
    private fun date(value: String) = LocalDate.parse(value)

    private fun chain(start: String, interval: RecurringInterval, anchorDay: Int, count: Int): List<String> =
        generateSequence(date(start)) { RecurringDates.next(it, interval, anchorDay) }.drop(1).take(count).map { it.toString() }.toList()

    @Test
    fun `weekly adds seven days, across a year end`() {
        assertEquals(listOf("2027-01-05", "2027-01-12"), chain("2026-12-29", RecurringInterval.WEEKLY, anchorDay = 29, count = 2))
    }

    @Test
    fun `monthly on the 1st`() {
        assertEquals(listOf("2027-01-01", "2027-02-01"), chain("2026-12-01", RecurringInterval.MONTHLY, anchorDay = 1, count = 2))
    }

    @Test
    fun `monthly anchor 31 clamps to short months and comes back`() {
        assertEquals(
            listOf("2026-11-30", "2026-12-31", "2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30"),
            chain("2026-10-31", RecurringInterval.MONTHLY, anchorDay = 31, count = 6),
        )
    }

    @Test
    fun `monthly anchor 30 in a leap February`() {
        assertEquals(
            listOf("2028-02-29", "2028-03-30"),
            chain("2028-01-30", RecurringInterval.MONTHLY, anchorDay = 30, count = 2),
        )
    }

    @Test
    fun `yearly on 29 February falls back to the 28th outside leap years`() {
        assertEquals(
            listOf("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"),
            chain("2024-02-29", RecurringInterval.YEARLY, anchorDay = 29, count = 4),
        )
    }

    @Test
    fun `yearly keeps the month and day`() {
        assertEquals(listOf("2027-06-15", "2028-06-15"), chain("2026-06-15", RecurringInterval.YEARLY, anchorDay = 15, count = 2))
    }

    @Test
    fun `an out-of-range anchor is clamped to the month`() {
        assertEquals(date("2026-12-01"), RecurringDates.next(date("2026-11-01"), RecurringInterval.MONTHLY, anchorDay = 0))
        assertEquals(date("2026-11-30"), RecurringDates.next(date("2026-10-31"), RecurringInterval.MONTHLY, anchorDay = 40))
    }
}
