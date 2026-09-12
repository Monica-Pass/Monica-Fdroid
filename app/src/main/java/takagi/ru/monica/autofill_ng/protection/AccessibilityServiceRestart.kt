package takagi.ru.monica.autofill_ng.protection

import takagi.ru.monica.BuildConfig

internal const val MONICA_ACCESSIBILITY_COMPONENT =
    BuildConfig.APPLICATION_ID + "/takagi.ru.monica.service.MonicaAccessibilityService"

internal interface AccessibilityServiceSettings {
    fun readEnabledServices(): String
    fun writeEnabledServices(value: String)
}

internal fun isMonicaAccessibilityComponent(component: String): Boolean {
    val packageName = component.substringBefore('/', "")
    val serviceName = component.substringAfter('/', "")
    val expandedName = if (serviceName.startsWith('.')) packageName + serviceName else serviceName
    return "$packageName/$expandedName" == MONICA_ACCESSIBILITY_COMPONENT
}

/** Only restarts an already enabled Monica service; never grants accessibility consent. */
internal class AccessibilityServiceRestart(
    private val settings: AccessibilityServiceSettings,
    private val awaitDisconnect: () -> Unit,
) {
    fun restart(): AccessibilityRecoveryResult {
        val original = runCatching { settings.readEnabledServices() }.getOrElse {
            return AccessibilityRecoveryResult.FAILED
        }
        val components = original.split(':')
        if (components.none(::isMonicaAccessibilityComponent)) {
            return AccessibilityRecoveryResult.NOT_ENABLED
        }
        val withoutMonica = components.filterNot(::isMonicaAccessibilityComponent).joinToString(":")
        if (runCatching { settings.readEnabledServices() }.getOrNull() != original) {
            return AccessibilityRecoveryResult.SETTINGS_CHANGED
        }

        var removedSuccessfully = false
        try {
            settings.writeEnabledServices(withoutMonica)
            removedSuccessfully = true
            awaitDisconnect()
        } catch (_: Exception) {
            // A failed write can still have reached SettingsProvider. Always inspect and restore.
        }

        return try {
            when (settings.readEnabledServices()) {
                withoutMonica -> {
                    // Restore the exact previous value only if nobody has changed the setting.
                    // Never overwrite another accessibility manager or a user's concurrent edit.
                    settings.writeEnabledServices(original)
                    if (settings.readEnabledServices() == original && removedSuccessfully) {
                        AccessibilityRecoveryResult.RESTART_REQUESTED
                    } else {
                        AccessibilityRecoveryResult.FAILED
                    }
                }
                original -> if (removedSuccessfully) {
                    AccessibilityRecoveryResult.SETTINGS_CHANGED
                } else {
                    AccessibilityRecoveryResult.FAILED
                }
                else -> AccessibilityRecoveryResult.SETTINGS_CHANGED
            }
        } catch (_: Exception) {
            AccessibilityRecoveryResult.FAILED
        }
    }
}
