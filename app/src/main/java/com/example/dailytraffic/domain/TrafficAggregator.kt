package com.example.dailytraffic.domain

import android.app.usage.NetworkStats

object TrafficAggregator {
    fun merge(
        mobile: Map<Int, TrafficBytes>,
        wifi: Map<Int, TrafficBytes>
    ): Map<Int, UidUsage> {
        val uids = mobile.keys + wifi.keys
        val result = LinkedHashMap<Int, UidUsage>(uids.size)
        for (uid in uids) {
            if (uid == NetworkStats.Bucket.UID_ALL) continue
            val mobileBytes = mobile[uid] ?: TrafficBytes.ZERO
            val wifiBytes = wifi[uid] ?: TrafficBytes.ZERO
            if (mobileBytes.total + wifiBytes.total > 0) {
                result[uid] = UidUsage(mobileBytes, wifiBytes)
            }
        }
        return result
    }

    fun sortRows(rows: List<TrafficRow>, filter: NetworkFilter): List<TrafficRow> =
        rows.asSequence()
            .filter { it.uid != NetworkStats.Bucket.UID_ALL && it.bytesFor(filter).total > 0 }
            .sortedWith(
                compareByDescending<TrafficRow> { it.bytesFor(filter).total }
                    .thenBy { it.label }
                    .thenBy { it.uid }
            )
            .toList()

    fun sortSharedGroups(
        groups: List<SharedUidGroup>,
        filter: NetworkFilter
    ): List<SharedUidGroup> =
        groups.asSequence()
            .filter { it.bytesFor(filter).total > 0 }
            .sortedWith(
                compareByDescending<SharedUidGroup> { it.bytesFor(filter).total }
                    .thenBy { it.packageNames.firstOrNull().orEmpty() }
                    .thenBy { it.uid }
            )
            .toList()
}
