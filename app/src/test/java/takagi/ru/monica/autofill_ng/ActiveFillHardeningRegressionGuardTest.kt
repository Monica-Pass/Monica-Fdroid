package takagi.ru.monica.autofill_ng

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveFillHardeningRegressionGuardTest {
    @Test
    fun accessibilityPasteUsesAProtectedTemporaryClipboard() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/service/MonicaAccessibilityService.kt"
        ).readText()

        assertTrue(serviceSource.contains("android.content.extra.IS_SENSITIVE"))
        assertTrue(serviceSource.contains("scheduleTemporaryClipboardRestore"))
        assertTrue(serviceSource.contains("ACTION_SET_SELECTION"))
        assertTrue(serviceSource.contains("shouldRestoreTemporaryClipboard"))
    }

    @Test
    fun notificationFillIsOptInThrottledAndBoundToTheDetectedApp() {
        val serviceSource = projectFile(
            "app/src/main/java/takagi/ru/monica/service/MonicaAccessibilityService.kt"
        ).readText()
        val helperSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/ActiveFillNotificationHelper.kt"
        ).readText()
        val pickerSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivityV2.kt"
        ).readText()
        val preferencesSource = projectFile(
            "app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPreferences.kt"
        ).readText()
        val settingsScreenSource = projectFile(
            "app/src/main/java/takagi/ru/monica/ui/screens/AutofillSettingsV2Screen.kt"
        ).readText()

        assertTrue(serviceSource.contains("activeFillNotificationEnabled"))
        assertTrue(serviceSource.contains("activeFillPromptThrottle.tryAcquire"))
        assertTrue(serviceSource.contains("entry.isLinkedToApp(packageName)"))
        assertFalse(serviceSource.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE"))
        assertTrue(helperSource.contains("EXTRA_MANUAL_TARGET_PACKAGE"))
        assertTrue(pickerSource.contains("EXTRA_MANUAL_TARGET_PACKAGE"))
        assertTrue(preferencesSource.contains("KEY_ACTIVE_FILL_NOTIFICATION_ENABLED"))
        assertTrue(preferencesSource.contains("preferences[KEY_ACTIVE_FILL_NOTIFICATION_ENABLED] ?: false"))
        assertTrue(settingsScreenSource.contains("setActiveFillNotificationEnabled"))
    }

    private fun projectFile(path: String): File {
        var dir = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        while (
            dir.parentFile != null &&
            !File(dir, "settings.gradle").exists() &&
            !File(dir, "settings.gradle.kts").exists()
        ) {
            dir = dir.parentFile!!.canonicalFile
        }
        return File(dir, path)
    }
}
