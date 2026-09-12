package takagi.ru.monica.autofill_ng.protection

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import takagi.ru.monica.service.MonicaAccessibilityService

/** Run on the task-owned emulator with the official Shizuku app authorized in ADB mode. */
@RunWith(AndroidJUnit4::class)
class ShizukuAccessibilityIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<AutofillProtectionActivity>? = null
    private var originalServices: String? = null
    private var originalAccessibility = 0
    private var originalPreferences: Map<String, *> = emptyMap<String, Any?>()
    private var prepared = false
    private val otherService = "com.android.systemui.accessibility.accessibilitymenu/.AccessibilityMenuService"

    @Before fun prepare() {
        assumeTrue("Requires authorized Shizuku in ADB mode", ShizukuAccessibilityRecovery.access() == ShizukuAccess.ADB_AUTHORIZED)
        assertEquals(2000, Shizuku.getUid())
        scenario = ActivityScenario.launch(AutofillProtectionActivity::class.java)
        originalServices = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        originalAccessibility = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        originalPreferences = context.getSharedPreferences("autofill_protection", Context.MODE_PRIVATE).all.toMap()
        scenario!!.onActivity { AutofillProtection.setEnabled(it, false) }
        prepared = true
    }

    @After fun restore() {
        if (prepared) {
            setServices(originalServices, originalAccessibility)
            context.getSharedPreferences("autofill_protection", Context.MODE_PRIVATE).edit().clear().apply {
                originalPreferences.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                    }
                }
            }.commit()
            if (originalPreferences["background_enabled"] == true) {
                scenario?.onActivity { AutofillProtection.restoreIfEnabled(it) }
            }
        }
        scenario?.close()
    }

    @Test fun realShizukuHelperReconnectsMonicaWithoutChangingAnotherService() = runBlocking {
        val original = "$otherService:$MONICA_ACCESSIBILITY_COMPONENT"
        setServices(original, 1)
        withTimeout(15_000) { MonicaAccessibilityService.connectionState.first { it } }
        assertEquals(AccessibilityRecoveryResult.RESTART_REQUESTED, callHelper())
        assertEquals(original, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        withTimeout(15_000) { MonicaAccessibilityService.connectionState.first { it } }
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        assertTrue(manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == "com.android.systemui.accessibility.accessibilitymenu" })
    }

    @Test fun realShizukuHelperRefusesToEnableDisabledMonica() = runBlocking {
        setServices(otherService, 1)
        withTimeout(15_000) { MonicaAccessibilityService.connectionState.first { !it } }
        assertEquals(AccessibilityRecoveryResult.NOT_ENABLED, callHelper())
        assertEquals(otherService, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test fun productionRecoveryReconnectsAnEnabledUnboundServiceAndHonorsCooldown() = runBlocking {
        val original = "$otherService:$MONICA_ACCESSIBILITY_COMPONENT"
        setServices(original, 1)
        withTimeout(15_000) { MonicaAccessibilityService.connectionState.first { it } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Suppress binding without revoking user consent, reproducing an enabled, unbound service.
        instrumentation.getUiAutomation(0)
        withTimeout(15_000) { MonicaAccessibilityService.connectionState.first { !it } }
        assertEquals(AccessibilityConnection.DISCONNECTED, AutofillProtection.snapshot(context).accessibility)
        context.getSharedPreferences("autofill_protection", Context.MODE_PRIVATE).edit()
            .putBoolean("background_enabled", true).putBoolean("shizuku_recovery_enabled", true)
            .remove("last_recovery_attempt").commit()

        val restored = CompletableDeferred<Unit>()
        var sawDisconnect = false
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val value = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                if (value == otherService) sawDisconnect = true
                if (sawDisconnect && value == original) restored.complete(Unit)
            }
        }
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer,
        )
        try {
            val recovery = async { ShizukuAccessibilityRecovery.recover(context) }
            withTimeout(20_000) { restored.await() }
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            assertEquals(AccessibilityRecoveryResult.CONNECTED, withTimeout(10_000) { recovery.await() })
            assertEquals(original, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))

            instrumentation.getUiAutomation(0)
            withTimeout(15_000) { MonicaAccessibilityService.connectionState.first { !it } }
            assertEquals(AccessibilityRecoveryResult.COOLDOWN, ShizukuAccessibilityRecovery.recover(context))
            assertEquals(original, Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        } finally {
            context.contentResolver.unregisterContentObserver(observer)
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        }
    }

    private fun setServices(value: String?, enabled: Int) {
        val automation = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            check(Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value))
            check(Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled))
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private suspend fun callHelper(): AccessibilityRecoveryResult {
        val ready = CompletableDeferred<IMonicaAccessibilityRecovery>()
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuAccessibilityUserService::class.java))
            .tag("monica-recovery-integration-${android.os.Process.myUid()}").version(1)
            .processNameSuffix("accessibility_recovery_test").daemon(true).debuggable(false)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                if (service != null) ready.complete(IMonicaAccessibilityRecovery.Stub.asInterface(service))
                else ready.completeExceptionally(IllegalStateException("No helper binder"))
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                ready.completeExceptionally(IllegalStateException("Helper disconnected"))
            }
        }
        return try {
            withContext(Dispatchers.Main) { Shizuku.bindUserService(args, connection) }
            val helper = withTimeout(10_000) { ready.await() }
            withContext(Dispatchers.IO) { AccessibilityRecoveryResult.fromCode(helper.restartMonicaAccessibility()) }
        } finally {
            withContext(Dispatchers.Main) { runCatching { Shizuku.unbindUserService(args, connection, true) } }
        }
    }
}
