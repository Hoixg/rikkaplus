package me.rerere.rikkahub.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import me.rerere.rikkahub.data.automation.AutomationAlarms
import java.util.Locale

/** System access is queried on resume; vendor autostart has no reliable public status API. */
data class BackgroundAccess(
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val batteryExempt: Boolean,
) {
    companion object {
        fun read(context: Context) = BackgroundAccess(
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            exactAlarms = AutomationAlarms.allowed(context),
            batteryExempt = context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName),
        )
    }
}

internal fun appDetailsIntent(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri(),
)

/** Try the actual launch: resolveActivity may hide a vendor app because of package visibility. */
internal fun openBackgroundSettings(context: Context, vararg candidates: Intent): Boolean {
    for (intent in candidates.toList() + appDetailsIntent(context)) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: android.content.ActivityNotFoundException) {
            // Settings components differ between Android versions and vendor ROMs.
        } catch (_: SecurityException) {
            // A vendor may expose a component that third-party apps cannot launch.
        }
    }
    return false
}

internal fun autoStartSettingsIntents(): List<Intent> {
    val components = when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
        "xiaomi", "redmi" -> listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        )
        "huawei", "honor" -> listOf(
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        "oppo", "oneplus", "realme" -> listOf(
            "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        )
        "vivo", "iqoo" -> listOf(
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        else -> emptyList()
    }
    return components.map { (pkg, activity) -> Intent().setComponent(ComponentName(pkg, activity)) }
}
