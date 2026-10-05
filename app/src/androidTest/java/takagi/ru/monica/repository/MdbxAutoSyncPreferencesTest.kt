package takagi.ru.monica.repository

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import takagi.ru.monica.workers.MdbxAutoSyncPreferences

class MdbxAutoSyncPreferencesTest {
    @Test fun manualDefaultPersistsPerVaultAndIdleBackoffSurvivesRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = -System.nanoTime()
        val other = id - 1
        val prefs = MdbxAutoSyncPreferences(context)
        try {
            assertFalse(prefs.isEnabled(id))
            prefs.setEnabled(id, true)
            assertTrue(MdbxAutoSyncPreferences(context).isEnabled(id))
            assertFalse(prefs.isEnabled(other))
            var now = 1_000_000L
            for (interval in listOf(60_000L, 120_000L, 240_000L, 300_000L, 300_000L)) {
                prefs.recordSuccess(id, changed = false, now = now)
                val reopened = MdbxAutoSyncPreferences(context)
                assertFalse(reopened.isPollDue(id, now + interval - 1))
                assertTrue(reopened.isPollDue(id, now + interval))
                now += interval
            }
            prefs.resetIdle(id)
            assertTrue(prefs.isPollDue(id, now))
            prefs.recordSuccess(id, changed = true, now = now)
            assertFalse(prefs.isPollDue(id, now + 59_999))
            assertTrue(prefs.isPollDue(id, now + 60_000))
            assertTrue("Clock rollback cannot suppress checks", prefs.isPollDue(id, now - 600_000))
            prefs.setEnabled(id, false)
            assertFalse(MdbxAutoSyncPreferences(context).isEnabled(id))
        } finally {
            context.getSharedPreferences("mdbx_auto_sync", 0).edit()
                .remove("enabled_$id").remove("idle_$id").remove("next_$id").commit()
        }
    }
}
