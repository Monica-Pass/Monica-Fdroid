package takagi.ru.monica.repository

import takagi.ru.monica.data.LocalMdbxDatabase
import takagi.ru.monica.data.MdbxTigaMode

/** Preserve old records without creating client replicas for unsupported Glitter vaults. */
internal fun requireMdbxAppReplicaAllowed(database: LocalMdbxDatabase) {
    check(database.tigaModeEnum != MdbxTigaMode.GLITTER && !MdbxClientModePolicy.isEnvelope(database.encryptedPassword)) {
        "Glitter is not supported by the Android client."
    }
}
