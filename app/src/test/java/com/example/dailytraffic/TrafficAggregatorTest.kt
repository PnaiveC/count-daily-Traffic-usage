package com.example.dailytraffic

import com.example.dailytraffic.domain.TrafficAggregator
import com.example.dailytraffic.domain.TrafficBytes
import com.example.dailytraffic.domain.TrafficRow
import com.example.dailytraffic.domain.NetworkFilter
import com.example.dailytraffic.domain.SharedUidGroup
import com.example.dailytraffic.domain.UidUsage
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
        assertEquals(UidUsage(TrafficBytes(10, 5), TrafficBytes(1, 2)), merged[100])
        assertEquals(UidUsage(TrafficBytes.ZERO, TrafficBytes(3, 4)), merged[102])
    }

    @Test
    fun sortsRowsByBytesThenLabelThenUid() {
        val rows = listOf(
            row(10, "B", 100),
            row(20, "A", 100),
            row(30, "A", 50),
            row(40, "C", 0)
        )

        val sorted = TrafficAggregator.sortRows(rows, NetworkFilter.ALL)

        assertEquals(listOf(20, 10, 30), sorted.map { it.uid })
    }

    @Test
    fun filtersRowsAndSortsBySelectedNetwork() {
        val rows = listOf(
            TrafficRow(1, "Mobile heavy", emptyList(), TrafficBytes(100, 0), TrafficBytes(1, 0), null),
            TrafficRow(2, "Wifi heavy", emptyList(), TrafficBytes(1, 0), TrafficBytes(200, 0), null),
            TrafficRow(3, "Wifi empty", emptyList(), TrafficBytes(50, 0), TrafficBytes.ZERO, null)
        )

        assertEquals(
            listOf(2, 1),
            TrafficAggregator.sortRows(rows, NetworkFilter.WIFI).map { it.uid }
        )
        assertEquals(
            listOf(1, 3, 2),
            TrafficAggregator.sortRows(rows, NetworkFilter.MOBILE).map { it.uid }
        )
    }

    @Test
    fun sortsSharedUidGroupsBySelectedNetwork() {
        val groups = listOf(
            SharedUidGroup(1, listOf("a", "b"), TrafficBytes(10, 0), TrafficBytes(1, 0)),
            SharedUidGroup(2, listOf("c", "d"), TrafficBytes(1, 0), TrafficBytes(20, 0))
        )

        assertEquals(
            listOf(2, 1),
            TrafficAggregator.sortSharedGroups(groups, NetworkFilter.WIFI).map { it.uid }
        )
    }

    private fun row(uid: Int, label: String, total: Long) = TrafficRow(
        uid = uid,
        label = label,
        packageNames = emptyList(),
        mobileBytes = TrafficBytes(total, 0),
        wifiBytes = TrafficBytes.ZERO,
        icon = null
    )
}
