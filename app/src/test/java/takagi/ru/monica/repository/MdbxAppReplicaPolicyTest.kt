package takagi.ru.monica.repository

import org.junit.Assert.assertThrows
import org.junit.Test
import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxTigaMode

class MdbxAppReplicaPolicyTest {
    private fun database(mode: MdbxTigaMode, envelope: String? = null) = LocalMdbxDatabase(
        name = "policy test", filePath = "unused", tigaMode = mode.name, encryptedPassword = envelope)

    @Test fun legacyModesRetainTheirIntegration() {
        listOf(MdbxTigaMode.SKY, MdbxTigaMode.MULTI, MdbxTigaMode.POWER).forEach {
            requireMdbxAppReplicaAllowed(database(it))
        }
    }

    @Test fun glitterRejectsOrdinaryAppStorageEvenBeforeAKeyExists() {
        assertThrows(IllegalStateException::class.java) { requireMdbxAppReplicaAllowed(database(MdbxTigaMode.GLITTER)) }
    }

    @Test fun hardwareEnvelopeCannotDowngradeViaStaleModeMetadata() {
        assertThrows(IllegalStateException::class.java) {
            requireMdbxAppReplicaAllowed(database(MdbxTigaMode.MULTI, "glitter-hw:v1:alias:iv:content"))
        }
    }

}
