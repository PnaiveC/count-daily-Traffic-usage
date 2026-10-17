package com.example.dailytraffic

import com.example.dailytraffic.domain.TrafficAggregator
import com.example.dailytraffic.domain.TrafficBytes
import com.example.dailytraffic.domain.TrafficRow
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficAggregatorTest {
    @Test
    fun mergesMobileAndWifiByUidAndFiltersEmptyRows() {
        val mobile = mapOf(
            100 to TrafficBytes(10, 5),
            101 to TrafficBytes(0, 0),
            -1 to TrafficBytes(99, 99)
        )
        val wifi = mapOf(
            100 to TrafficBytes(1, 2),
            102 to TrafficBytes(3, 4)
        )

        val merged = TrafficAggregator.merge(mobile, wifi)

        assertEquals(2, merged.size)
        assertEquals(TrafficBytes(11, 7), merged[100])
        assertEquals(TrafficBytes(3, 4), merged[102])
    }

    @Test
    fun sortsRowsByBytesThenLabelThenUid() {
        val rows = listOf(
            row(10, "B", 100),
            row(20, "A", 100),
            row(30, "A", 50),
            row(40, "C", 0)
        )

        val sorted = TrafficAggregator.sortRows(rows)

        assertEquals(listOf(20, 10, 30), sorted.map { it.uid })
    }

    private fun row(uid: Int, label: String, total: Long) = TrafficRow(
        uid = uid,
        label = label,
        packageNames = emptyList(),
        bytes = TrafficBytes(total, 0),
        icon = null
    )
}
