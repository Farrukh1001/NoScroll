package com.noscroll.domain.wellbeing

import com.noscroll.domain.notify.MessageCatalogue
import com.noscroll.domain.notify.MessageTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * "Time saved" is the easiest number in a wellbeing app to inflate, so these tests are mostly about
 * what the calculator refuses to say: no first-day saving, no partial-day-versus-full-day
 * comparison, and no negative saving dressed up as a positive one.
 */
class TimeSavedTest {

    private val calculator = TimeSavedCalculator()
    private val today = LocalDate.of(2026, 8, 11)

    private fun aggregateOf(vararg days: Pair<LocalDate, List<Long>>): UsageAggregate =
        UsageAggregate(
            perDayPerPackage = days.associate { (date, hours) ->
                date to mapOf("app" to hours.sum())
            },
            perDayHourly = days.toMap(),
        )

    /** Minutes of usage placed in the hours before [untilHour]. */
    private fun hoursWithMinutes(minutes: Long, untilHour: Int = 10): List<Long> =
        List(24) { hour -> if (hour < untilHour) minutes * 60_000L / untilHour else 0L }

    @Test
    fun `a genuine reduction against yesterday is reported`() {
        val aggregate = aggregateOf(
            today.minusDays(1) to hoursWithMinutes(180),
            today to hoursWithMinutes(165),
        )
        val result = calculator.compareWithYesterday(aggregate, today, cutoffHour = 10, cutoffMinute = 0)
        assertTrue(result is TimeSavedResult.Saved)
        assertEquals(15, (result as TimeSavedResult.Saved).minutes)
        assertEquals(SavingBaseline.YESTERDAY, result.baseline)
    }

    @Test
    fun `the specification's example wording is produced`() {
        val result = TimeSavedResult.Saved(15, SavingBaseline.YESTERDAY)
        val message = MessageCatalogue.timeSaved(result, MessageTone.CALM)
        assertEquals(
            "You saved 15 minutes compared with yesterday by this time. That's 15 minutes back in your day.",
            message!!.text,
        )
    }

    @Test
    fun `higher usage is stated factually and is never a negative saving`() {
        val aggregate = aggregateOf(
            today.minusDays(1) to hoursWithMinutes(100),
            today to hoursWithMinutes(130),
        )
        val result = calculator.compareWithYesterday(aggregate, today, 10, 0)
        assertTrue(result is TimeSavedResult.Higher)
        assertEquals(30, (result as TimeSavedResult.Higher).minutes)

        val message = MessageCatalogue.timeSaved(result, MessageTone.CALM)!!.text
        assertTrue(message.contains("above"))
        // No shame, no failure language.
        assertFalse(message.contains("failed"))
        assertFalse(message.contains("-"))
    }

    @Test
    fun `there is no saving on the first day`() {
        // No yesterday in the data at all: the honest answer is to say nothing.
        val aggregate = aggregateOf(today to hoursWithMinutes(60))
        val result = calculator.compareWithYesterday(aggregate, today, 10, 0)
        assertEquals(TimeSavedResult.InsufficientData, result)
        assertNull("nothing is shown", MessageCatalogue.timeSaved(result, MessageTone.CALM))
    }

    @Test
    fun `the seven day average needs a minimum of real history`() {
        val twoDays = aggregateOf(
            today.minusDays(1) to hoursWithMinutes(100),
            today.minusDays(2) to hoursWithMinutes(100),
            today to hoursWithMinutes(50),
        )
        assertEquals(
            TimeSavedResult.InsufficientData,
            calculator.compareWithAverage(twoDays, today, 10, 0, minimumDays = 3),
        )
    }

    @Test
    fun `the seven day average compares at the same cutoff`() {
        val days = (1..7).map { today.minusDays(it.toLong()) to hoursWithMinutes(120) }.toTypedArray()
        val aggregate = aggregateOf(*days, today to hoursWithMinutes(98))
        val result = calculator.compareWithAverage(aggregate, today, 10, 0)
        assertTrue(result is TimeSavedResult.Saved)
        assertEquals(22, (result as TimeSavedResult.Saved).minutes)
        assertEquals(SavingBaseline.SEVEN_DAY_AVERAGE, result.baseline)
    }

    @Test
    fun `a partial day is never compared against a full one`() {
        // Yesterday has 12 hours of usage spread across the whole day; today has 2 hours by 10:00.
        // Comparing at the 10:00 cutoff must only count yesterday's usage before 10:00.
        val yesterdayAllDay = List(24) { 30 * 60_000L }
        val todayMorning = List(24) { hour -> if (hour < 10) 12 * 60_000L else 0L }
        val aggregate = aggregateOf(
            today.minusDays(1) to yesterdayAllDay,
            today to todayMorning,
        )
        val result = calculator.compareWithYesterday(aggregate, today, 10, 0)

        // Yesterday to 10:00 = 10 * 30 = 300 minutes. Today = 10 * 12 = 120 minutes.
        assertEquals(180, (result as TimeSavedResult.Saved).minutes)
    }

    @Test
    fun `identical usage reports level, not a saving`() {
        val aggregate = aggregateOf(
            today.minusDays(1) to hoursWithMinutes(100),
            today to hoursWithMinutes(100),
        )
        val result = calculator.compareWithYesterday(aggregate, today, 10, 0)
        assertTrue(result is TimeSavedResult.Level)
    }

    @Test
    fun `blocked attempts are never converted into minutes`() {
        // The catalogue can report a count, and only a count.
        val message = MessageCatalogue.blockedCount(8)!!
        assertTrue(message.text.contains("8"))
        assertFalse("no minutes are invented from blocked openings", message.text.contains("minute"))
        assertNull(MessageCatalogue.blockedCount(0))
    }
}
