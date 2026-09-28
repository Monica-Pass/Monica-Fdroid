package takagi.ru.monica.utils

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import takagi.ru.monica.data.*

@RunWith(AndroidJUnit4::class)
class LocalBackupReplacementTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun stats(failed: Int = 0) = RestoreApplyStats(0, 0, 0, 1, 0, failed, 0, 0, 0, emptyList(), emptyList())

    @Test fun failureCountsRollbackRowsAndCascadedCustomFields() = checkRollback { db ->
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "new", itemData = "NEW"))
        stats(failed = 1)
    }

    @Test fun writeExceptionRollsBackRows() = checkRollback { db ->
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "new", itemData = "NEW"))
        throw IllegalStateException("synthetic write failure")
    }

    @Test fun cancellationRollsBackRows() = checkRollback { db ->
        db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "new", itemData = "NEW"))
        throw CancellationException("synthetic cancellation")
    }

    private fun checkRollback(write: suspend (PasswordDatabase) -> RestoreApplyStats) = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PasswordDatabase::class.java).build()
        try {
            val pw = PasswordEntry(title = "old password", username = "alice", website = "", password = "original")
            val pwId = db.passwordEntryDao().insert(pw)
            db.customFieldDao().insert(CustomField(entryId = pwId, title = "Recovery", value = "original field"))
            val otp = SecureItem(itemType = ItemType.TOTP, title = "old otp", itemData = "ORIGINAL")
            val id = db.secureItemDao().insertItem(otp)
            val beforeFields = db.customFieldDao().getFieldsByEntryId(pwId).first()
            val result = runCatching { LocalBackupReplacement.apply(db) { write(db) } }
            assertTrue("Incomplete writes must not commit", result.isFailure)
            assertEquals(otp.copy(id = id), db.secureItemDao().getItemById(id))
            assertEquals(pw.copy(id = pwId), db.passwordEntryDao().getPasswordEntryById(pwId))
            assertEquals(beforeFields, db.customFieldDao().getFieldsByEntryId(pwId).first())
            assertEquals(listOf(id), db.secureItemDao().getActiveLocalItemsByTypeSync(ItemType.TOTP).map { it.id })
        } finally { db.close() }
    }

    @Test fun successfulReplacementOnlyReplacesLocalRowsAndSurvivesReopen() = runBlocking {
        val name = "local-replacement-${java.util.UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
        try {
            val local = db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "old", itemData = "OLD"))
            val foreign = SecureItem(itemType = ItemType.TOTP, title = "external", itemData = "EXTERNAL", keepassDatabaseId = 77)
            val foreignId = db.secureItemDao().insertItem(foreign)
            LocalBackupReplacement.apply(db) {
                db.secureItemDao().insertItem(SecureItem(itemType = ItemType.TOTP, title = "new", itemData = "NEW"))
                stats()
            }
            db.close()
            db = Room.databaseBuilder(context, PasswordDatabase::class.java, name).build()
            assertNull(db.secureItemDao().getItemById(local))
            assertEquals(foreign.copy(id = foreignId), db.secureItemDao().getItemById(foreignId))
            assertEquals("NEW", db.secureItemDao().getActiveLocalItemsByTypeSync(ItemType.TOTP).single().itemData)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
