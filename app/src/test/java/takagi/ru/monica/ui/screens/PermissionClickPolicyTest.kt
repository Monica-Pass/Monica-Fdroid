package takagi.ru.monica.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test
import takagi.ru.monica.data.model.PermissionStatus

class PermissionClickPolicyTest {

    @Test
    fun deniedRuntimePermissionUsesNativeRequest() {
        assertEquals(
            PermissionClickAction.REQUEST_RUNTIME_PERMISSION,
            resolvePermissionClickAction("CAMERA", PermissionStatus.DENIED)
        )
        assertEquals(
            PermissionClickAction.REQUEST_RUNTIME_PERMISSION,
            resolvePermissionClickAction("NOTIFICATION", PermissionStatus.DENIED)
        )
    }

    @Test
    fun servicePermissionsOpenTheirDedicatedSystemPages() {
        assertEquals(
            PermissionClickAction.OPEN_AUTOFILL_SETTINGS,
            resolvePermissionClickAction("AUTOFILL", PermissionStatus.DENIED)
        )
        assertEquals(
            PermissionClickAction.OPEN_ACCESSIBILITY_SETTINGS,
            resolvePermissionClickAction("ACCESSIBILITY", PermissionStatus.DENIED)
        )
    }

    @Test
    fun unavailablePermissionIgnoresClicks() {
        assertEquals(
            PermissionClickAction.IGNORE,
            resolvePermissionClickAction("CAMERA", PermissionStatus.UNAVAILABLE)
        )
    }
}
