package com.remotecontrol.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Session 10a: battery optimisation helpers. The Agent must be exempt ("Unrestricted") or Doze / OEM battery managers
 * will freeze it while the screen is off and the Controller can no longer reach it.
 */
object BatteryHelper {
    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Shows the system "allow to run in background" dialog; falls back to the battery settings list, then to App info. */
    fun requestExemption(activity: Activity) {
        val pkg = activity.packageName
        val attempts = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$pkg")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")),
        )
        for (i in attempts) if (tryStart(activity, i)) return
    }

    /** Vendor "auto-start" / "protected apps" screens. Best effort: returns false when none of the known ones exists. */
    fun openVendorAutoStart(activity: Activity): Boolean {
        val known = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",
            "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        )
        for ((pkg, cls) in known) if (tryStart(activity, Intent().setComponent(ComponentName(pkg, cls)))) return true
        return false
    }

    fun openAppDetails(activity: Activity) {
        tryStart(activity, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}")))
    }

    private fun tryStart(activity: Activity, i: Intent): Boolean = try {
        activity.startActivity(i)
        true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false } catch (_: RuntimeException) { false }
}
