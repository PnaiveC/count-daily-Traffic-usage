package com.example.dailytraffic.ui

import com.example.dailytraffic.domain.DailyTraffic
import com.example.dailytraffic.domain.NetworkFilter
import com.example.dailytraffic.domain.SharedUidGroup
import com.example.dailytraffic.domain.TrafficRow
import com.example.dailytraffic.domain.WeeklyUsage

sealed interface TrendState {
    data object None : TrendState
    data object Loading : TrendState
    data class Success(val usage: WeeklyUsage) : TrendState
    data class Failure(val message: String) : TrendState
}

data class TrafficListState(
    val traffic: DailyTraffic,
    val filter: NetworkFilter,
    val otherExpanded: Boolean,
    val expandedUid: Int?,
    val trend: TrendState
)

sealed interface TrafficListItem {
    data class AppRow(val row: TrafficRow) : TrafficListItem
    data class OtherRow(
        val totalBytes: Long,
        val groupCount: Int,
        val packageCount: Int
    ) : TrafficListItem

    data class SharedGroupRow(val group: SharedUidGroup) : TrafficListItem
}
