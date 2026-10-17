package com.example.dailytraffic.data

import android.app.usage.NetworkStats
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Process
import com.example.dailytraffic.R

data class ResolvedUid(
    val label: String,
    val packageNames: List<String>,
    val icon: Drawable?
)

class PackageResolver(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager: PackageManager = appContext.packageManager
    private val defaultIcon: Drawable = packageManager.defaultActivityIcon

    fun resolve(uid: Int): ResolvedUid {
        if (uid == NetworkStats.Bucket.UID_ALL) {
            return ResolvedUid(
                appContext.getString(R.string.uid_all),
                emptyList(),
                defaultIcon
            )
        }
        if (uid == NetworkStats.Bucket.UID_TETHERING) {
            return ResolvedUid(
                appContext.getString(R.string.uid_tethering),
                emptyList(),
                defaultIcon
            )
        }
        if (uid == NetworkStats.Bucket.UID_REMOVED) {
            return ResolvedUid(
                appContext.getString(R.string.uid_removed),
                emptyList(),
                defaultIcon
            )
        }
        if (uid < 0) {
            return ResolvedUid(
                appContext.getString(R.string.uid_system_entry, uid),
                emptyList(),
                defaultIcon
            )
        }

        val packageNames = try {
            packageManager.getPackagesForUid(uid)
                ?.filter { it.isNotBlank() }
                ?.toList()
                .orEmpty()
        } catch (_: RuntimeException) {
            emptyList()
        }

        if (packageNames.isEmpty()) {
            val label = if (uid < Process.FIRST_APPLICATION_UID) {
                appContext.getString(R.string.uid_system, uid)
            } else {
                appContext.getString(R.string.uid_unknown, uid)
            }
            return ResolvedUid(label, emptyList(), defaultIcon)
        }

        if (packageNames.size > 1) {
            return ResolvedUid(
                appContext.getString(R.string.uid_multiple_apps, packageNames.size),
                packageNames,
                defaultIcon
            )
        }

        val packageName = packageNames.first()
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            val label = info.loadLabel(packageManager).toString()
                .takeIf { it.isNotBlank() }
                ?: packageName
            val icon = try {
                info.loadIcon(packageManager)
            } catch (_: RuntimeException) {
                defaultIcon
            }
            ResolvedUid(label, packageNames, icon)
        } catch (_: PackageManager.NameNotFoundException) {
            ResolvedUid(packageName, packageNames, defaultIcon)
        } catch (_: RuntimeException) {
            ResolvedUid(packageName, packageNames, defaultIcon)
        }
    }
}
