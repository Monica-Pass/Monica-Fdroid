package takagi.ru.monica.autofill_ng.protection

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.util.Locale
import takagi.ru.monica.service.MonicaAccessibilityService

enum class ProtectionSettingsDestination { ACCESSIBILITY, BATTERY, AUTOSTART, NOTIFICATIONS, SHIZUKU }

object AutofillProtectionSettings {
    fun open(context: Context, destination: ProtectionSettingsDestination): Boolean {
        val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        val candidates = when (destination) {
            ProtectionSettingsDestination.ACCESSIBILITY -> listOf(
                Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                    .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, MonicaAccessibilityService::class.java)),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), appDetails,
            )
            ProtectionSettingsDestination.BATTERY -> listOfNotNull(
                if (context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true) null
                else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails,
            )
            ProtectionSettingsDestination.NOTIFICATIONS -> listOf(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                appDetails,
            )
            ProtectionSettingsDestination.AUTOSTART -> autostartIntents() + appDetails
            ProtectionSettingsDestination.SHIZUKU -> listOfNotNull(
                context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api"),
                Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")),
            )
        }
        return candidates.any { candidate ->
            runCatching {
                context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
        }
    }

    private fun autostartIntents(): List<Intent> {
        val components = when (Build.MANUFACTURER.lowercase(Locale.ROOT)) {
            "xiaomi", "redmi", "poco" -> listOf(
                "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
            )
            "huawei", "honor" -> listOf(
                "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            )
            "oppo", "realme", "oneplus" -> listOf(
                "com.oplus.safecenter/com.oplus.safecenter.startupapp.StartupAppListActivity",
                "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            )
            "vivo", "iqoo" -> listOf(
                "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            )
            else -> emptyList()
        }
        return components.mapNotNull { flattened ->
            ComponentName.unflattenFromString(flattened)?.let { Intent().setComponent(it) }
        }
    }
}
