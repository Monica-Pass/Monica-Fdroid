package takagi.ru.monica.autofill_ng.protection

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.security.SessionManager

@RunWith(AndroidJUnit4::class)
class AutofillProtectionServiceTest {
    @get:Rule val compose = createAndroidComposeRule<AutofillProtectionActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var originalPreferences: Map<String, *> = emptyMap<String, Any?>()

    @Before fun prepare() {
        val prefs = context.getSharedPreferences("autofill_protection", Context.MODE_PRIVATE)
        originalPreferences = prefs.all.toMap()
        compose.runOnIdle { AutofillProtection.setEnabled(context, false) }
        compose.waitUntil(5_000) { AutofillProtection.runtime.value == ProtectionRuntime.STOPPED }
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
    }

    @After fun restore() {
        compose.runOnIdle { AutofillProtection.setEnabled(context, false) }
        compose.waitUntil(5_000) { AutofillProtection.runtime.value == ProtectionRuntime.STOPPED }
        context.getSharedPreferences("autofill_protection", Context.MODE_PRIVATE).edit().clear().apply {
            originalPreferences.forEach { (key, value) ->
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Long -> putLong(key, value)
                }
            }
        }.commit()
        if (originalPreferences["background_enabled"] == true) {
            compose.runOnIdle { AutofillProtection.restoreIfEnabled(context) }
        }
    }

    @Test fun noOptInMeansNoBootOrNormalLaunchProtection() {
        compose.runOnIdle {
            assertFalse(AutofillProtection.restoreIfEnabled(context))
            AutofillProtectionReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        }
        assertEquals(ProtectionRuntime.STOPPED, AutofillProtection.runtime.value)
        assertFalse(AutofillProtection.isEnhancedRecoveryEnabled(context))
    }

    @Test fun foregroundProtectionHasAWorkingStopActionAndDoesNotUnlockTheVault() {
        compose.runOnIdle {
            SessionManager.markLocked()
            assertTrue(AutofillProtection.setEnabled(context, true))
        }
        compose.waitUntil(10_000) { AutofillProtection.runtime.value == ProtectionRuntime.RUNNING }
        val manager = context.getSystemService(NotificationManager::class.java)
        compose.waitUntil(5_000) { manager.activeNotifications.any { it.id == AutofillProtectionService.NOTIFICATION_ID } }
        val notification = manager.activeNotifications.first { it.id == AutofillProtectionService.NOTIFICATION_ID }.notification
        assertFalse(SessionManager.isUnlocked.value)
        assertTrue(notification.actions.isNotEmpty())
        notification.actions.first().actionIntent.send()
        compose.waitUntil(5_000) { !AutofillProtection.isEnabled(context) && AutofillProtection.runtime.value == ProtectionRuntime.STOPPED }
        compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == AutofillProtectionService.NOTIFICATION_ID } }
        assertFalse(SessionManager.isUnlocked.value)
    }

    @Test fun persistedOptInRestoresFromBootAndStopsBothProtectionModesWhenDisabled() {
        val prefs = context.getSharedPreferences("autofill_protection", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("background_enabled", true).putBoolean("shizuku_recovery_enabled", true).commit()
        compose.runOnIdle { AutofillProtectionReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED)) }
        compose.waitUntil(10_000) { AutofillProtection.runtime.value == ProtectionRuntime.RUNNING }
        compose.runOnIdle { AutofillProtection.setEnabled(context, false) }
        compose.waitUntil(5_000) { AutofillProtection.runtime.value == ProtectionRuntime.STOPPED }
        assertFalse(AutofillProtection.isEnhancedRecoveryEnabled(context))
        compose.runOnIdle { AutofillProtectionReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED)) }
        assertEquals(ProtectionRuntime.STOPPED, AutofillProtection.runtime.value)
    }

    @Test fun arbitraryBroadcastsCannotStartProtection() {
        context.getSharedPreferences("autofill_protection", Context.MODE_PRIVATE).edit().putBoolean("background_enabled", true).commit()
        compose.runOnIdle { AutofillProtectionReceiver().onReceive(context, Intent("untrusted.action")) }
        assertEquals(ProtectionRuntime.STOPPED, AutofillProtection.runtime.value)
    }

    @Test fun privilegedEntrypointsAreProtectedInTheInstalledManifest() {
        val provider = context.packageManager.getProviderInfo(
            ComponentName(context.packageName, "rikka.shizuku.ShizukuProvider"), 0,
        )
        assertTrue(provider.exported)
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.readPermission)
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.writePermission)
        val receiver = context.packageManager.getReceiverInfo(ComponentName(context, AutofillProtectionReceiver::class.java), 0)
        val service = context.packageManager.getServiceInfo(ComponentName(context, AutofillProtectionService::class.java), 0)
        assertFalse(receiver.exported)
        assertFalse(service.exported)
        assertTrue(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains("android.permission.RECEIVE_BOOT_COMPLETED"))
    }

    @Test fun helperCannotRunInsideAnOrdinaryApplicationProcess() {
        var rejected = false
        try {
            ShizukuAccessibilityUserService(context)
        } catch (_: IllegalStateException) {
            rejected = true
        }
        assertTrue(rejected)
    }
}
