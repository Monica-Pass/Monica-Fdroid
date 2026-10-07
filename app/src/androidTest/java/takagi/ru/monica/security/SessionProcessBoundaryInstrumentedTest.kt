package takagi.ru.monica.security

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.utils.SettingsManager
import takagi.ru.monica.autofill_ng.auth.*

/** Two host-driven phases separated by a real force-stop; test marker contains no vault data. */
class SessionProcessBoundaryInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val marker = context.getSharedPreferences("session_process_test", 0)
    private val scope = AutofillGrantContext("example.test", null, null, null)
    @Test fun prepareUnlockedProcess() = runBlocking {
        check(android.os.Process.myUid() / 100000 > 0)
        // A test without an activity can begin while the user-switch keyguard is
        // still showing. Dismiss it through a real foreground window first.
        val host = androidx.test.core.app.ActivityScenario.launch<takagi.ru.monica.suggestions.CommonInfoTestActivity>(
            android.content.Intent(context, takagi.ru.monica.suggestions.CommonInfoTestActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        host.use { scenario ->
        val keyguard = context.getSystemService(android.app.KeyguardManager::class.java)
        scenario.onActivity { keyguard.requestDismissKeyguard(it, null) }
        kotlinx.coroutines.withTimeout(10000) {
            while (keyguard.isKeyguardLocked) kotlinx.coroutines.delay(50)
        }
        val settings = SettingsManager(context)
        val old = settings.settingsFlow.first()
        assertFalse("Previous interrupted test needs cleanup", marker.contains("pid"))
        marker.edit().putInt("pid", android.os.Process.myPid()).putInt("timeout", old.autoLockMinutes)
            .putBoolean("keep", old.autofillKeepUnlocked).putBoolean("auth", old.autofillAuthRequired).commit()
        settings.updateAutoLockMinutes(-1)
        settings.updateAutofillKeepUnlocked(true)
        settings.updateAutofillAuthRequired(true)
        check(SecurityManager(context).unlockVaultWithPassword("Monica-Autofill-Test-2026!"))
        SessionManager.updateAutoLockTimeout(-1)
        SessionManager.markUnlocked()
        AutofillSessionGrants.grant(scope, enabled = true)
        assertTrue(SessionManager.canSkipVerification(context))
        assertTrue(AutofillSessionGrants.isGranted(scope))
        assertTrue(context.getSharedPreferences("monica_session_state", 0).getBoolean("unlocked", false))
        }
    }
    @Test fun newProcessStartsLockedEvenWithUnlimitedTimeout() = runBlocking {
        check(android.os.Process.myUid() / 100000 > 0)
        assertTrue(marker.contains("pid"))
        try {
            assertNotEquals(marker.getInt("pid", -1), android.os.Process.myPid())
            assertFalse(SessionManager.isUnlocked.value)
            assertFalse(SessionManager.canSkipVerification(context))
            assertFalse(SecurityManager.hasRuntimeUnlockCache())
            assertFalse(AutofillSessionGrants.isGranted(scope))
            assertFalse(context.getSharedPreferences("monica_session_state", 0).getBoolean("unlocked", true))
        } finally {
            val settings = SettingsManager(context)
            settings.updateAutoLockMinutes(marker.getInt("timeout", 5))
            settings.updateAutofillKeepUnlocked(marker.getBoolean("keep", false))
            settings.updateAutofillAuthRequired(marker.getBoolean("auth", true))
            marker.edit().clear().commit()
        }
    }
}
