package com.example.dailytraffic.domain

import android.graphics.drawable.Drawable

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
    val bytes: TrafficBytes,
    val icon: Drawable?
)

data class DailyTraffic(
    val dayStartMillis: Long,
    val dayEndMillis: Long,
    val deviceMobile: TrafficBytes,
    val deviceWifi: TrafficBytes,
    val rows: List<TrafficRow>
) {
    val deviceTotal: Long
        get() = deviceMobile.total + deviceWifi.total
}

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
