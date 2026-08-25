package takagi.ru.monica.ui.screens

import takagi.ru.monica.data.model.PermissionStatus

internal enum class PermissionClickAction {
    REQUEST_RUNTIME_PERMISSION,
    OPEN_AUTOFILL_SETTINGS,
    OPEN_ACCESSIBILITY_SETTINGS,
    OPEN_BIOMETRIC_SETTINGS,
    OPEN_APP_SETTINGS,
    SHOW_GRANTED,
    IGNORE,
}

internal fun resolvePermissionClickAction(
    permissionId: String,
    status: PermissionStatus,
): PermissionClickAction {
    if (status == PermissionStatus.UNAVAILABLE) return PermissionClickAction.IGNORE

    return when (permissionId) {
        "AUTOFILL" -> PermissionClickAction.OPEN_AUTOFILL_SETTINGS
        "ACCESSIBILITY" -> PermissionClickAction.OPEN_ACCESSIBILITY_SETTINGS
        "BIOMETRIC" -> if (status == PermissionStatus.GRANTED) {
            PermissionClickAction.SHOW_GRANTED
        } else {
            PermissionClickAction.OPEN_BIOMETRIC_SETTINGS
        }
        "CAMERA", "STORAGE", "NOTIFICATION", "PHONE_STATE" -> {
            if (status == PermissionStatus.GRANTED) {
                PermissionClickAction.OPEN_APP_SETTINGS
            } else {
                PermissionClickAction.REQUEST_RUNTIME_PERMISSION
            }
        }
        "INTERNET", "NETWORK_STATE", "VIBRATE" -> PermissionClickAction.SHOW_GRANTED
        else -> PermissionClickAction.OPEN_APP_SETTINGS
    }
}
