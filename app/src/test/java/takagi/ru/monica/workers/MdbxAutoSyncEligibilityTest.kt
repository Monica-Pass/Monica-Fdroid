package takagi.ru.monica.workers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxEngineType
import takagi.ru.monica.data.MdbxSourceType
import takagi.ru.monica.data.MdbxTigaMode

class MdbxAutoSyncEligibilityTest {
    private fun row(mode: MdbxTigaMode, source: MdbxSourceType, password: String? = null) =
        LocalMdbxDatabase(name = "eligibility fixture", filePath = "unused",
            engineType = MdbxEngineType.RUST_MDBX2.name, tigaMode = mode.name,
            sourceType = source.name, encryptedPassword = password)

    @Test fun glitterCannotEnterAutomaticSyncForAnySource() {
        MdbxSourceType.entries.forEach { source ->
            assertFalse(MdbxAutoSyncWorker.eligible(row(MdbxTigaMode.GLITTER, source)))
        }
    }

    @Test fun originalModesKeepTheirRemoteEligibility() {
        listOf(MdbxTigaMode.SKY, MdbxTigaMode.MULTI, MdbxTigaMode.POWER).forEach { mode ->
            listOf(MdbxSourceType.REMOTE_WEBDAV, MdbxSourceType.REMOTE_ONEDRIVE).forEach { source ->
                assertTrue(MdbxAutoSyncWorker.eligible(row(mode, source)))
            }
            assertFalse(MdbxAutoSyncWorker.eligible(row(mode, MdbxSourceType.LOCAL_INTERNAL)))
            assertFalse(MdbxAutoSyncWorker.eligible(row(mode, MdbxSourceType.LOCAL_EXTERNAL)))
        }
    }

    @Test fun oldHardwareCredentialCannotBypassTheGateWithStaleModeMetadata() {
        assertFalse(MdbxAutoSyncWorker.eligible(
            row(MdbxTigaMode.SKY, MdbxSourceType.REMOTE_WEBDAV, "glitter-hw:v1:old-record")))
    }
}
