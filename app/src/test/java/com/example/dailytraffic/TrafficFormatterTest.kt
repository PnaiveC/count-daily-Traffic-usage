package com.example.dailytraffic

import com.example.dailytraffic.util.TrafficFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficFormatterTest {
    @Test
    fun formatsByteBoundaries() {
        assertEquals("0 B", TrafficFormatter.format(0L))
        assertEquals("1023 B", TrafficFormatter.format(1023L))
        assertEquals("1 KB", TrafficFormatter.format(1024L))
        assertEquals("1.5 KB", TrafficFormatter.format(1536L))
        assertEquals("1 MB", TrafficFormatter.format(1024L * 1024L))
        assertEquals("1 GB", TrafficFormatter.format(1024L * 1024L * 1024L))
    }
}
