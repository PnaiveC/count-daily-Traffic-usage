package com.example.dailytraffic.data

import android.net.ConnectivityManager
import android.os.RemoteException
import com.example.dailytraffic.domain.DailyUsagePoint
import com.example.dailytraffic.domain.DailyTraffic
import com.example.dailytraffic.domain.ErrorKind
import com.example.dailytraffic.domain.LoadState
import com.example.dailytraffic.domain.NetworkFilter
import com.example.dailytraffic.domain.SharedUidGroup
import com.example.dailytraffic.domain.TrafficAggregator
import com.example.dailytraffic.domain.TrafficRow
import com.example.dailytraffic.domain.WeeklyUsage
import com.example.dailytraffic.time.DayRange
import com.example.dailytraffic.time.DayRangeProvider

@Suppress("DEPRECATION")
class DailyTrafficRepository(
    private val stats: NetworkStatsDataSource,
    private val packages: PackageResolver,
    private val dayRangeProvider: DayRangeProvider
) {
    fun load(range: DayRange): LoadState<DailyTraffic> {
        if (range.dayStartMillis >= range.endMillis) {
            return LoadState.Failure(ErrorKind.INVALID_RANGE)
        }

        return try {
            val mobile = stats.queryDevice(ConnectivityManager.TYPE_MOBILE, range)
            val wifi = stats.queryDevice(ConnectivityManager.TYPE_WIFI, range)
            val mobileUids = stats.queryUid(ConnectivityManager.TYPE_MOBILE, range)
            val wifiUids = stats.queryUid(ConnectivityManager.TYPE_WIFI, range)

            val merged = TrafficAggregator.merge(mobileUids, wifiUids)
            val appRows = ArrayList<TrafficRow>()
            val sharedGroups = ArrayList<SharedUidGroup>()
            for ((uid, usage) in merged) {
                val resolved = packages.resolve(uid)
                if (resolved.isSharedUid) {
                    sharedGroups += SharedUidGroup(
                        uid = uid,
                        packageNames = resolved.packageNames,
                        mobileBytes = usage.mobile,
                        wifiBytes = usage.wifi
                    )
                } else {
                    appRows += TrafficRow(
                        uid = uid,
                        label = resolved.label,
                        packageNames = resolved.packageNames,
                        mobileBytes = usage.mobile,
                        wifiBytes = usage.wifi,
                        icon = resolved.icon
                    )
                }
            }

            LoadState.Success(
                DailyTraffic(
                    dayStartMillis = range.dayStartMillis,
                    dayEndMillis = range.endMillis,
                    deviceMobile = mobile,
                    deviceWifi = wifi,
                    appRows = appRows,
                    sharedGroups = sharedGroups
                )
            )
        } catch (_: SecurityException) {
            LoadState.PermissionDenied
        } catch (_: RemoteException) {
            LoadState.Failure(ErrorKind.REMOTE_SERVICE)
        } catch (_: IllegalArgumentException) {
            LoadState.Failure(ErrorKind.INVALID_RANGE)
        } catch (_: Exception) {
            LoadState.Failure(ErrorKind.UNKNOWN)
        }
    }

    fun loadWeekly(
        uid: Int,
        endDayStartMillis: Long,
        filter: NetworkFilter
    ): LoadState<WeeklyUsage> {
        val points = ArrayList<DailyUsagePoint>(7)
        for (offset in 6 downTo 0) {
            val dayStart = dayRangeProvider.addDays(endDayStartMillis, -offset)
            val range = dayRangeProvider.rangeFor(dayStart)
            if (range == null) {
                points += DailyUsagePoint(dayStart, null)
                continue
            }
            val bytes = try {
                queryUidForDay(range, uid, filter)
            } catch (e: SecurityException) {
                return LoadState.PermissionDenied
            } catch (_: Exception) {
                null
            }
            points += DailyUsagePoint(dayStart, bytes)
        }
        return LoadState.Success(
            WeeklyUsage(
                uid = uid,
                endDayStartMillis = endDayStartMillis,
                filter = filter,
                points = points
            )
        )
    }

    private fun queryUidForDay(
        range: DayRange,
        uid: Int,
        filter: NetworkFilter
    ) = when (filter) {
        NetworkFilter.ALL -> uidBytes(ConnectivityManager.TYPE_MOBILE, range, uid) +
            uidBytes(ConnectivityManager.TYPE_WIFI, range, uid)

        NetworkFilter.MOBILE -> uidBytes(ConnectivityManager.TYPE_MOBILE, range, uid)
        NetworkFilter.WIFI -> uidBytes(ConnectivityManager.TYPE_WIFI, range, uid)
    }

    private fun uidBytes(networkType: Int, range: DayRange, uid: Int) =
        stats.queryUid(networkType, range)[uid] ?: com.example.dailytraffic.domain.TrafficBytes.ZERO
}
