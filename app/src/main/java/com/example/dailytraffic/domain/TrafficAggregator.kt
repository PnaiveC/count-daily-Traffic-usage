package com.example.dailytraffic.domain

import android.app.usage.NetworkStats

object TrafficAggregator {
    fun merge(
        mobile: Map<Int, TrafficBytes>,
        wifi: Map<Int, TrafficBytes>
    ): Map<Int, TrafficBytes> {
        val uids = mobile.keys + wifi.keys
        val result = LinkedHashMap<Int, TrafficBytes>(uids.size)
        for (uid in uids) {
            if (uid == NetworkStats.Bucket.UID_ALL) continue
            val bytes = (mobile[uid] ?: TrafficBytes.ZERO) +
                (wifi[uid] ?: TrafficBytes.ZERO)
            if (bytes.total > 0) {
                result[uid] = bytes
            }
        }
        return result
    }

    fun sortRows(rows: List<TrafficRow>): List<TrafficRow> =
        rows.asSequence()
            .filter { it.uid != NetworkStats.Bucket.UID_ALL && it.bytes.total > 0 }
            .sortedWith(
                compareByDescending<TrafficRow> { it.bytes.total }
                    .thenBy { it.label }
                    .thenBy { it.uid }
            )
            .toList()
}
