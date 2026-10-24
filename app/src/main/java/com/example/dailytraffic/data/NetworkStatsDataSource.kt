package com.example.dailytraffic.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import com.example.dailytraffic.domain.TrafficBytes
import com.example.dailytraffic.time.DayRange

interface NetworkStatsDataSource {
    fun queryDevice(networkType: Int, range: DayRange): TrafficBytes
    fun queryUid(networkType: Int, range: DayRange): Map<Int, TrafficBytes>
}

class AndroidNetworkStatsDataSource(context: Context) : NetworkStatsDataSource {
    private val manager =
        context.applicationContext.getSystemService(NetworkStatsManager::class.java)
            ?: error("NetworkStatsManager unavailable")

    override fun queryDevice(networkType: Int, range: DayRange): TrafficBytes {
        val bucket = manager.querySummaryForDevice(
            networkType,
            null,
            range.dayStartMillis,
            range.endMillis
        ) ?: error("Device traffic query unavailable")
        return TrafficBytes(
            rx = bucket.rxBytes.coerceAtLeast(0L),
            tx = bucket.txBytes.coerceAtLeast(0L)
        )
    }

    override fun queryUid(networkType: Int, range: DayRange): Map<Int, TrafficBytes> {
        val stats = manager.querySummary(
            networkType,
            null,
            range.dayStartMillis,
            range.endMillis
        ) ?: error("UID traffic query unavailable")

        val totals = HashMap<Int, TrafficBytes>()
        try {
            val bucket = NetworkStats.Bucket()
            while (stats.hasNextBucket()) {
                if (!stats.getNextBucket(bucket)) break
                val uid = bucket.uid
                val current = totals[uid] ?: TrafficBytes.ZERO
                totals[uid] = current + TrafficBytes(
                    rx = bucket.rxBytes.coerceAtLeast(0L),
                    tx = bucket.txBytes.coerceAtLeast(0L)
                )
            }
        } finally {
            stats.close()
        }
        return totals
    }

}
