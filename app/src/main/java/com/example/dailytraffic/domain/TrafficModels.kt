package com.example.dailytraffic.domain

import android.graphics.drawable.Drawable

enum class NetworkFilter {
    ALL,
    MOBILE,
    WIFI
}

data class TrafficBytes(
    val rx: Long,
    val tx: Long
) {
    val total: Long get() = rx + tx

    operator fun plus(other: TrafficBytes): TrafficBytes =
        TrafficBytes(rx + other.rx, tx + other.tx)

    companion object {
        val ZERO = TrafficBytes(0L, 0L)
    }
}

data class TrafficRow(
    val uid: Int,
    val label: String,
    val packageNames: List<String>,
    val mobileBytes: TrafficBytes,
    val wifiBytes: TrafficBytes,
    val icon: Drawable?
) {
    fun bytesFor(filter: NetworkFilter): TrafficBytes = when (filter) {
        NetworkFilter.ALL -> mobileBytes + wifiBytes
        NetworkFilter.MOBILE -> mobileBytes
        NetworkFilter.WIFI -> wifiBytes
    }

    val canShowTrend: Boolean get() = uid >= 0
}

data class SharedUidGroup(
    val uid: Int,
    val packageNames: List<String>,
    val mobileBytes: TrafficBytes,
    val wifiBytes: TrafficBytes
) {
    fun bytesFor(filter: NetworkFilter): TrafficBytes = when (filter) {
        NetworkFilter.ALL -> mobileBytes + wifiBytes
        NetworkFilter.MOBILE -> mobileBytes
        NetworkFilter.WIFI -> wifiBytes
    }
}

data class UidUsage(
    val mobile: TrafficBytes,
    val wifi: TrafficBytes
) {
    fun bytesFor(filter: NetworkFilter): TrafficBytes = when (filter) {
        NetworkFilter.ALL -> mobile + wifi
        NetworkFilter.MOBILE -> mobile
        NetworkFilter.WIFI -> wifi
    }
}

data class DailyTraffic(
    val dayStartMillis: Long,
    val dayEndMillis: Long,
    val deviceMobile: TrafficBytes,
    val deviceWifi: TrafficBytes,
    val appRows: List<TrafficRow>,
    val sharedGroups: List<SharedUidGroup>
) {
    fun deviceBytes(filter: NetworkFilter): TrafficBytes = when (filter) {
        NetworkFilter.ALL -> deviceMobile + deviceWifi
        NetworkFilter.MOBILE -> deviceMobile
        NetworkFilter.WIFI -> deviceWifi
    }
}

data class DailyUsagePoint(
    val dayStartMillis: Long,
    val bytes: TrafficBytes?
)

data class WeeklyUsage(
    val uid: Int,
    val endDayStartMillis: Long,
    val filter: NetworkFilter,
    val points: List<DailyUsagePoint>
)

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Success<T>(val value: T) : LoadState<T>
    data object PermissionDenied : LoadState<Nothing>
    data class Failure(val kind: ErrorKind) : LoadState<Nothing>
}

enum class ErrorKind {
    SECURITY,
    REMOTE_SERVICE,
    INVALID_RANGE,
    UNKNOWN
}
