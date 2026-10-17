package com.example.dailytraffic

import com.example.dailytraffic.time.DayRangeProvider
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayRangeProviderTest {
    @Test
    fun todayRangeEndsAtNow() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = millis(zone, 2026, Calendar.OCTOBER, 17, 15, 30, 0)
        val provider = provider(zone, now)
        val range = provider.rangeFor(provider.startOfDay(now))!!

        assertEquals(millis(zone, 2026, Calendar.OCTOBER, 17, 0, 0, 0), range.dayStartMillis)
        assertEquals(now, range.endMillis)
    }

    @Test
    fun pastDayUsesNextMidnight() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = millis(zone, 2026, Calendar.OCTOBER, 17, 15, 30, 0)
        val past = millis(zone, 2026, Calendar.OCTOBER, 16, 0, 0, 0)
        val provider = provider(zone, now)
        val range = provider.rangeFor(past)!!

        assertEquals(past, range.dayStartMillis)
        assertEquals(millis(zone, 2026, Calendar.OCTOBER, 17, 0, 0, 0), range.endMillis)
    }

    @Test
    fun futureDayIsRejected() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = millis(zone, 2026, Calendar.OCTOBER, 17, 15, 30, 0)
        val provider = provider(zone, now)

        assertNull(provider.rangeFor(millis(zone, 2026, Calendar.OCTOBER, 18, 0, 0, 0)))
    }

    @Test
    fun daylightSavingDayIsNotTwentyFourHours() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = millis(zone, 2026, Calendar.MARCH, 9, 12, 0, 0)
        val provider = provider(zone, now)
        val dstDay = millis(zone, 2026, Calendar.MARCH, 8, 0, 0, 0)
        val range = provider.rangeFor(dstDay)!!

        assertEquals(23L * 60L * 60L * 1000L, range.endMillis - range.dayStartMillis)
    }

    @Test
    fun yearBoundaryUsesNextMidnight() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val now = millis(zone, 2026, Calendar.JANUARY, 1, 12, 0, 0)
        val provider = provider(zone, now)
        val previousYear = millis(zone, 2025, Calendar.DECEMBER, 31, 0, 0, 0)
        val range = provider.rangeFor(previousYear)!!

        assertEquals(millis(zone, 2026, Calendar.JANUARY, 1, 0, 0, 0), range.endMillis)
        assertTrue(provider.isToday(provider.startOfDay(now)))
    }

    private fun provider(zone: TimeZone, now: Long) = DayRangeProvider(
        timeZoneProvider = { zone },
        nowProvider = { now }
    )

    private fun millis(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month, day, hour, minute, second)
    }.timeInMillis
}
