package com.example.dailytraffic.time

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class DayRange(
    val dayStartMillis: Long,
    val endMillis: Long
)

class DayRangeProvider(
    private val timeZoneProvider: () -> TimeZone = { TimeZone.getDefault() },
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {
    fun now(): Long = nowProvider()

    fun startOfDay(millis: Long): Long = calendar(millis).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun addDays(dayStartMillis: Long, days: Int): Long =
        calendar(dayStartMillis).apply {
            add(Calendar.DAY_OF_MONTH, days)
        }.timeInMillis

    fun isToday(dayStartMillis: Long, nowMillis: Long = nowProvider()): Boolean =
        startOfDay(dayStartMillis) == startOfDay(nowMillis)

    fun rangeFor(dayStartMillis: Long, nowMillis: Long = nowProvider()): DayRange? {
        val start = startOfDay(dayStartMillis)
        val todayStart = startOfDay(nowMillis)
        if (start > todayStart) return null

        val end = if (start == todayStart) {
            maxOf(nowMillis, start + 1L)
        } else {
            addDays(start, 1)
        }
        return DayRange(dayStartMillis = start, endMillis = end)
    }

    fun calendarFor(dayStartMillis: Long): Calendar = calendar(dayStartMillis)

    fun millisForDate(year: Int, month: Int, dayOfMonth: Int): Long =
        calendar(0L).apply {
            clear()
            set(year, month, dayOfMonth)
        }.timeInMillis

    fun formatDay(dayStartMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).apply {
            timeZone = timeZoneProvider()
        }.format(Date(dayStartMillis))

    private fun calendar(millis: Long): Calendar =
        Calendar.getInstance(timeZoneProvider()).apply {
            timeInMillis = millis
        }
}
