package com.example.dailytraffic.data

import android.net.ConnectivityManager
import android.os.RemoteException
import com.example.dailytraffic.domain.DailyTraffic
import com.example.dailytraffic.domain.ErrorKind
import com.example.dailytraffic.domain.LoadState
import com.example.dailytraffic.domain.TrafficAggregator
import com.example.dailytraffic.domain.TrafficRow
import com.example.dailytraffic.time.DayRange

@Suppress("DEPRECATION")
class DailyTrafficRepository(
    private val stats: NetworkStatsDataSource,
    private val packages: PackageResolver
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
            val rows = TrafficAggregator.sortRows(
                merged.map { (uid, bytes) ->
                    val resolved = packages.resolve(uid)
                    TrafficRow(
                        uid = uid,
                        label = resolved.label,
                        packageNames = resolved.packageNames,
                        bytes = bytes,
                        icon = resolved.icon
                    )
                }
            )

            LoadState.Success(
                DailyTraffic(
                    dayStartMillis = range.dayStartMillis,
                    dayEndMillis = range.endMillis,
                    deviceMobile = mobile,
                    deviceWifi = wifi,
                    rows = rows
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
}
