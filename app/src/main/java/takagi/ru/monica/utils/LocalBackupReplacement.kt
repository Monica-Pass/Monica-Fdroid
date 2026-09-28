package takagi.ru.monica.utils

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import takagi.ru.monica.data.ItemType
import takagi.ru.monica.data.PasswordDatabase
import takagi.ru.monica.steam.data.SteamDatabase

/** Keep original rows (including cascaded fields/attachments) until replacement has fully succeeded. */
internal object LocalBackupReplacement {
    suspend fun apply(
        database: PasswordDatabase,
        steamDatabase: SteamDatabase? = null,
        restore: suspend () -> RestoreApplyStats,
    ): RestoreApplyStats {
        suspend fun replaceLocal(): RestoreApplyStats = database.withTransaction {
            database.passwordEntryDao().deleteAllLocalPasswordEntries()
            ItemType.entries.filter { it != ItemType.PASSWORD }.forEach {
                database.secureItemDao().deleteAllLocalItemsByType(it)
            }
            database.passkeyDao().deleteAllLocalPasskeys()
            restore().also { result ->
                check(result.passwordFailed + result.secureItemFailed + result.passkeyFailed +
                    result.steamAccountFailed + result.nativeTokenFailed == 0) { "Incomplete replacement restored no local changes" }
                currentCoroutineContext().ensureActive()
            }
        }
        return if (steamDatabase == null) replaceLocal() else steamDatabase.withTransaction {
            steamDatabase.steamAccountDao().deleteAll()
            replaceLocal()
        }
    }
}
