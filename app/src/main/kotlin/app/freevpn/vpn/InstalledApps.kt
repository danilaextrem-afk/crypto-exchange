package app.freevpn.vpn

import android.content.Intent
import android.content.pm.PackageManager

data class AppEntry(val packageName: String, val label: String)

object InstalledApps {
    private fun launcherIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

    fun launcherPackages(pm: PackageManager): Set<String> =
        pm.queryIntentActivities(launcherIntent(), 0).map { it.activityInfo.packageName }.toSet()

    fun list(pm: PackageManager): List<AppEntry> =
        pm.queryIntentActivities(launcherIntent(), 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
}
