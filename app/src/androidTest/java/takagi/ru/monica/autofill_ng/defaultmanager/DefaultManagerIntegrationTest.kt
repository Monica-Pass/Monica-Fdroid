package takagi.ru.monica.autofill_ng.defaultmanager

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DefaultManagerIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realShizukuSwitchNoOpStaleSnapshotAndRestore() = runBlocking {
        assertEquals("Real Shizuku authorization is required", DefaultManagerAccess.READY, ShizukuDefaultManager.access())
        val original = ShizukuDefaultManager.read(context)
        val prefs = context.getSharedPreferences("default_password_manager", Context.MODE_PRIVATE)
        val backup = prefs.getString("previous", null)
        try {
            val applied = ShizukuDefaultManager.apply(context, original, true, true)
            assertEquals(setOf(canonicalComponent(MonicaDefaultServices.credential)), components(applied[SettingKey.PRIMARY]))
            assertEquals(setOf(canonicalComponent(MonicaDefaultServices.autofill)), components(applied[SettingKey.AUTOFILL]))
            assertTrue(components(applied[SettingKey.CREDENTIALS]).containsAll(components(original[SettingKey.CREDENTIALS])))
            assertTrue(applied.equivalentTo(ShizukuDefaultManager.read(context)))
            val saved = prefs.getString("previous", null)
            ShizukuDefaultManager.apply(context, applied, true, true)
            assertEquals("No-op must preserve restoration point", saved, prefs.getString("previous", null))
            if (!original.equivalentTo(applied)) {
                assertTrue(runCatching { ShizukuDefaultManager.apply(context, original, true, true) }.isFailure)
                assertTrue(applied.equivalentTo(ShizukuDefaultManager.read(context)))
                assertTrue(original.equivalentTo(ShizukuDefaultManager.restore(context, applied)))
                assertNull(ShizukuDefaultManager.previous(context))
            }
        } finally {
            // Test-owned emulator only: guarantee the exact prior settings survive any assertion failure.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try { SettingKey.entries.forEach { android.provider.Settings.Secure.putString(context.contentResolver, it.wireName, original[it]) } }
            finally { automation.dropShellPermissionIdentity() }
            prefs.edit().apply { if (backup == null) remove("previous") else putString("previous", backup) }.commit()
        }
    }

    @Test fun snapshotCodecPreservesMissingValuesAndRejectsMalformedTargets() {
        val original = SettingsSnapshot(android.os.Process.myUid() / 100000, SettingKey.entries.associateWith { null })
        assertEquals(original, snapshotFromJson(original.toJson()))
        assertEquals(original, original.toBundle().toSnapshot())
        val invalid = original.toBundle().apply { putString(SettingKey.AUTOFILL.wireName, "a/.B:b/.C") }
        assertTrue(runCatching { invalid.toSnapshot() }.isFailure)
        assertTrue(runCatching { original.toBundle().apply { remove("userId") }.toSnapshot() }.isFailure)
        assertEquals(context.packageName, MonicaDefaultServices.credential.substringBefore('/'))
    }
}
